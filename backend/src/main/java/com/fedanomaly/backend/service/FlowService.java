package com.fedanomaly.backend.service;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import com.fedanomaly.backend.model.NetworkFlow;
import com.fedanomaly.backend.model.Packet;
import com.fedanomaly.backend.model.NodeStatus;
import com.fedanomaly.backend.repository.NetworkFlowRepository;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.UUID;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import org.springframework.web.client.RestTemplate;

@Service
public class FlowService {
    private final NetworkFlowRepository flowRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final com.fedanomaly.backend.repository.NetworkNodeRepository nodeRepository;
    private final AttackService attackService;
    private final RestTemplate restTemplate;
    
    @Value("${ML_SERVICE_URL:http://localhost:5000}")
    private String mlServiceUrl;
    
    // Key: "src-dest-protocol-srcPort-destPort"
    private final Map<String, NetworkFlow> activeFlows = new ConcurrentHashMap<>();

    public FlowService(NetworkFlowRepository flowRepository, SimpMessagingTemplate messagingTemplate, 
                       com.fedanomaly.backend.repository.NetworkNodeRepository nodeRepository,
                       AttackService attackService) {
        this.flowRepository = flowRepository;
        this.messagingTemplate = messagingTemplate;
        this.nodeRepository = nodeRepository;
        this.attackService = attackService;
        this.restTemplate = new RestTemplate();
    }

    public void receivePacket(Packet p) {
        String flowKey = String.format("%s-%s-%s-%d-%d", p.getSource(), p.getDest(), p.getProtocol(), p.getSourcePort(), p.getDestPort());
        
        NetworkFlow flow = activeFlows.computeIfAbsent(flowKey, k -> {
            NetworkFlow newFlow = new NetworkFlow();
            newFlow.setFlowId(UUID.randomUUID());
            newFlow.setSimulationId(UUID.randomUUID());
            newFlow.setTimestamp(Instant.now());
            newFlow.setSourceNode(p.getSource());
            newFlow.setDestNode(p.getDest());
            newFlow.setProtocol(p.getProtocol());
            newFlow.setSourcePort(p.getSourcePort());
            newFlow.setDestPort(p.getDestPort());
            newFlow.setLabel("NORMAL"); // All packets start as NORMAL — ML decides classification
            newFlow.setDurationMs(0);
            newFlow.setPacketCount(0);
            newFlow.setTotalBytes(0);
            return newFlow;
        });

        // Update flow statistics
        synchronized(flow) {
            flow.setPacketCount(flow.getPacketCount() + 1);
            flow.setTotalBytes(flow.getTotalBytes() + p.getBytes());
            flow.setDurationMs(flow.getDurationMs() + 10); 
            flow.setAvgPacketSize((double) flow.getTotalBytes() / flow.getPacketCount());
            
            double seconds = Math.max(0.001, flow.getDurationMs() / 1000.0);
            flow.setPacketsPerSec(flow.getPacketCount() / seconds);
            flow.setBytesPerSec(flow.getTotalBytes() / seconds);
        }

        // Broadcast packet (for live packet feed)
        messagingTemplate.convertAndSend("/topic/packets", p);
    }

    public void processFlows() {
        if (activeFlows.isEmpty()) return;
        
        List<NetworkFlow> flowsToSave = new ArrayList<>(activeFlows.values());
        activeFlows.clear(); // new time window

        // ── ML-based prediction (sole detection authority) ───────────────────
        // Group flows by DESTINATION node for anomaly detection.
        // Anomalies are detected at the destination where attack traffic arrives.
        Map<String, List<Map<String, Object>>> nodeFlowsMap = new HashMap<>();
        Map<String, List<NetworkFlow>> nodeFlowObjects = new HashMap<>();
        
        for (NetworkFlow f : flowsToSave) {
            Map<String, Object> map = new HashMap<>();
            map.put("flow_id", f.getFlowId() != null ? f.getFlowId().toString() : UUID.randomUUID().toString());
            map.put("duration_ms", f.getDurationMs());
            map.put("packet_count", f.getPacketCount());
            map.put("total_bytes", f.getTotalBytes());
            map.put("avg_packet_size", f.getAvgPacketSize());
            map.put("packets_per_sec", f.getPacketsPerSec());
            map.put("bytes_per_sec", f.getBytesPerSec());
            map.put("dest_port", f.getDestPort()); // For port_diversity calculation
            
            String destNode = f.getDestNode();
            nodeFlowsMap.computeIfAbsent(destNode, k -> new ArrayList<>()).add(map);
            nodeFlowObjects.computeIfAbsent(destNode, k -> new ArrayList<>()).add(f);
        }

        // Request predictions from ML service
        for (Map.Entry<String, List<Map<String, Object>>> entry : nodeFlowsMap.entrySet()) {
            String nodeId = entry.getKey();
            List<Map<String, Object>> flowMaps = entry.getValue();
            List<NetworkFlow> nodeFlows = nodeFlowObjects.get(nodeId);
            
            Map<String, Object> req = new HashMap<>();
            req.put("node_id", nodeId);
            req.put("flow_data", flowMaps);

            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> response = restTemplate.postForObject(
                    mlServiceUrl + "/predict", req, Map.class);
                    
                if (response != null && response.containsKey("predictions")) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> preds = (List<Map<String, Object>>) response.get("predictions");
                    
                    int anomalyCount = 0;
                    double maxScore = 0;
                    Map<String, Object> bestExplanation = null;
                    
                    for (int i = 0; i < preds.size() && i < nodeFlows.size(); i++) {
                        Map<String, Object> pred = preds.get(i);
                        boolean isAnomaly = (Boolean) pred.get("is_anomaly");
                        double score = ((Number) pred.get("anomaly_score")).doubleValue();
                        
                        NetworkFlow f = nodeFlows.get(i);
                        f.setLabel(isAnomaly ? "ANOMALY" : "NORMAL");
                        f.setAnomalyScore(score);
                        
                        if (isAnomaly) {
                            anomalyCount++;
                            if (Math.abs(score) > Math.abs(maxScore)) {
                                maxScore = score;
                                @SuppressWarnings("unchecked")
                                Map<String, Object> expl = (Map<String, Object>) pred.get("explanation");
                                bestExplanation = expl;
                            }
                        }
                    }
                    
                    // If ML detected anomalies in traffic arriving at this node, mark it
                    if (anomalyCount > 0) {
                        final int finalAnomalyCount = anomalyCount;
                        final double finalMaxScore = maxScore;
                        final Map<String, Object> finalExplanation = bestExplanation;
                        
                        nodeRepository.findById(nodeId).ifPresent(node -> {
                            if (node.getStatus() != NodeStatus.QUARANTINED) {
                                node.setStatus(NodeStatus.SUSPICIOUS);
                                nodeRepository.save(node);
                                messagingTemplate.convertAndSend("/topic/nodes", nodeRepository.findAll());
                                
                                // Build rich detection event
                                Map<String, Object> event = new HashMap<>();
                                event.put("type", "ANOMALY_DETECTED");
                                event.put("node", nodeId);
                                event.put("anomalyScore", Math.round(finalMaxScore * 10000.0) / 10000.0);
                                event.put("anomalyCount", finalAnomalyCount);
                                event.put("totalFlows", flowMaps.size());
                                event.put("timestamp", Instant.now().toString());
                                
                                // Add XAI explanation
                                if (finalExplanation != null && !finalExplanation.isEmpty()) {
                                    event.put("explanation", finalExplanation);
                                    String topFeature = finalExplanation.keySet().iterator().next();
                                    event.put("message", "ML detected anomaly: abnormal " + topFeature + 
                                        " (" + finalAnomalyCount + "/" + flowMaps.size() + " flows flagged)");
                                } else {
                                    event.put("message", "ML detected anomalous traffic pattern (" + 
                                        finalAnomalyCount + "/" + flowMaps.size() + " flows flagged)");
                                }
                                
                                // Add traffic context
                                double avgPps = flowMaps.stream()
                                    .mapToDouble(m -> ((Number) m.get("packets_per_sec")).doubleValue())
                                    .average().orElse(0);
                                double avgBps = flowMaps.stream()
                                    .mapToDouble(m -> ((Number) m.get("bytes_per_sec")).doubleValue())
                                    .average().orElse(0);
                                Map<String, Object> trafficStats = new HashMap<>();
                                trafficStats.put("avgPacketsPerSec", Math.round(avgPps * 10.0) / 10.0);
                                trafficStats.put("avgBytesPerSec", Math.round(avgBps * 10.0) / 10.0);
                                trafficStats.put("totalPackets", flowMaps.stream()
                                    .mapToLong(m -> ((Number) m.get("packet_count")).longValue()).sum());
                                event.put("trafficStats", trafficStats);
                                
                                // Add active attack info if known
                                Map<String, String> attacks = attackService.getActiveAttacks();
                                if (attacks.containsKey(nodeId)) {
                                    event.put("attackType", attacks.get(nodeId));
                                }
                                
                                messagingTemplate.convertAndSend("/topic/events", event);
                            }
                        });
                    }
                }
            } catch (Exception e) {
                // ML service down or model not trained yet — no detection possible
            }
        }
        
        flowRepository.saveAll(flowsToSave);
        
        // Broadcast flows
        for (NetworkFlow f : flowsToSave) {
            messagingTemplate.convertAndSend("/topic/flows", f);
        }
    }
}
