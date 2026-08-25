package com.fedanomaly.backend.service;
import org.springframework.stereotype.Service;
import com.fedanomaly.backend.model.NetworkFlow;
import com.fedanomaly.backend.model.Packet;
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
    private final RestTemplate restTemplate;
    
    // Key: "src-dest-protocol-srcPort-destPort"
    private final Map<String, NetworkFlow> activeFlows = new ConcurrentHashMap<>();

    public FlowService(NetworkFlowRepository flowRepository, SimpMessagingTemplate messagingTemplate, com.fedanomaly.backend.repository.NetworkNodeRepository nodeRepository) {
        this.flowRepository = flowRepository;
        this.messagingTemplate = messagingTemplate;
        this.nodeRepository = nodeRepository;
        this.restTemplate = new RestTemplate();
    }

    public void receivePacket(Packet p) {
        String flowKey = String.format("%s-%s-%s-%d-%d", p.getSource(), p.getDest(), p.getProtocol(), p.getSourcePort(), p.getDestPort());
        
        NetworkFlow flow = activeFlows.computeIfAbsent(flowKey, k -> {
            NetworkFlow newFlow = new NetworkFlow();
            newFlow.setFlowId(UUID.randomUUID());
            newFlow.setSimulationId(UUID.randomUUID()); // or use global
            newFlow.setTimestamp(Instant.now());
            newFlow.setSourceNode(p.getSource());
            newFlow.setDestNode(p.getDest());
            newFlow.setProtocol(p.getProtocol());
            newFlow.setSourcePort(p.getSourcePort());
            newFlow.setDestPort(p.getDestPort());
            newFlow.setLabel(p.getLabel());
            newFlow.setDurationMs(0);
            newFlow.setPacketCount(0);
            newFlow.setTotalBytes(0);
            return newFlow;
        });

        // Update flow
        synchronized(flow) {
            flow.setPacketCount(flow.getPacketCount() + 1);
            flow.setTotalBytes(flow.getTotalBytes() + p.getBytes());
            // simplistic duration
            flow.setDurationMs(flow.getDurationMs() + 10); 
            flow.setAvgPacketSize((double) flow.getTotalBytes() / flow.getPacketCount());
            
            double seconds = Math.max(0.001, flow.getDurationMs() / 1000.0);
            flow.setPacketsPerSec(flow.getPacketCount() / seconds);
            flow.setBytesPerSec(flow.getTotalBytes() / seconds);
        }

        // Broadcast packet
        messagingTemplate.convertAndSend("/topic/packets", p);
    }

    public void processFlows() {
        if (activeFlows.isEmpty()) return;
        
        List<NetworkFlow> flowsToSave = new ArrayList<>(activeFlows.values());
        activeFlows.clear(); // new time window

        // ── Step 1: Label-based immediate detection ───────────────────────
        // Only mark the DESTINATION node suspicious when it receives ATTACK traffic.
        // The source (attacker) loses trust only if it's also a source of many attack flows.
        Map<String, Integer> nodeAttackCountDest = new HashMap<>();
        Map<String, Integer> nodeAttackCountSrc  = new HashMap<>();
        for (NetworkFlow f : flowsToSave) {
            if ("ATTACK".equals(f.getLabel())) {
                nodeAttackCountDest.merge(f.getDestNode(), 1, Integer::sum);
                nodeAttackCountSrc.merge(f.getSourceNode(), 1, Integer::sum);
            }
        }
        // Mark destination nodes that received significant attack traffic
        nodeAttackCountDest.forEach((nodeId, count) -> {
            if (count >= 3) { // threshold to avoid false positives from random normal traffic
                nodeRepository.findById(nodeId).ifPresent(node -> {
                    if (node.getStatus() != com.fedanomaly.backend.model.NodeStatus.QUARANTINED) {
                        node.setStatus(com.fedanomaly.backend.model.NodeStatus.SUSPICIOUS);
                        node.setTrustScore(Math.max(0, node.getTrustScore() - 5.0));
                        nodeRepository.save(node);
                        messagingTemplate.convertAndSend("/topic/nodes", nodeRepository.findAll());
                        Map<String, Object> event = new HashMap<>();
                        event.put("type", "ANOMALY_DETECTED");
                        event.put("node", node.getId());
                        event.put("message", "Attack traffic detected targeting " + node.getName());
                        event.put("timestamp", java.time.Instant.now().toString());
                        messagingTemplate.convertAndSend("/topic/events", event);
                    }
                });
            }
        });
        // Mark source nodes that sent many attack packets (compromised internal nodes only)
        nodeAttackCountSrc.forEach((nodeId, count) -> {
            // Do not self-flag inet-gw since it acts as the external attacker origin
            if (nodeId.equals("inet-gw")) return;
            if (count >= 20) { // only flag sources that sent a LOT of attack packets
                nodeRepository.findById(nodeId).ifPresent(node -> {
                    if (node.getStatus() != com.fedanomaly.backend.model.NodeStatus.QUARANTINED) {
                        node.setStatus(com.fedanomaly.backend.model.NodeStatus.SUSPICIOUS);
                        node.setTrustScore(Math.max(0, node.getTrustScore() - 2.5));
                        nodeRepository.save(node);
                    }
                });
            }
        });

        // ── Step 2: ML-based prediction (reinforces detection) ────────────
        Map<String, List<Map<String, Object>>> nodeFlowsMap = new HashMap<>();
        for (NetworkFlow f : flowsToSave) {
            Map<String, Object> map = new HashMap<>();
            map.put("flow_id", f.getId() != null ? f.getId().toString() : UUID.randomUUID().toString());
            map.put("duration_ms", f.getDurationMs());
            map.put("packet_count", f.getPacketCount());
            map.put("total_bytes", f.getTotalBytes());
            map.put("avg_packet_size", f.getAvgPacketSize());
            map.put("packets_per_sec", f.getPacketsPerSec());
            map.put("bytes_per_sec", f.getBytesPerSec());
            nodeFlowsMap.computeIfAbsent(f.getSourceNode(), k -> new ArrayList<>()).add(map);
        }

        // Request predictions
        for (Map.Entry<String, List<Map<String, Object>>> entry : nodeFlowsMap.entrySet()) {
            String nodeId = entry.getKey();
            Map<String, Object> req = new HashMap<>();
            req.put("node_id", nodeId);
            req.put("flow_data", entry.getValue());

            try {
                Map response = restTemplate.postForObject("http://localhost:5000/predict", req, Map.class);
                if (response != null && response.containsKey("predictions")) {
                    List<Map<String, Object>> preds = (List<Map<String, Object>>) response.get("predictions");
                    boolean hasAnomaly = false;
                    for (int i = 0; i < preds.size(); i++) {
                        Map<String, Object> pred = preds.get(i);
                        boolean isAnomaly = (Boolean) pred.get("is_anomaly");
                        double score = ((Number) pred.get("anomaly_score")).doubleValue();
                        
                        NetworkFlow f = flowsToSave.stream().filter(fl -> fl.getSourceNode().equals(nodeId)).skip(i).findFirst().orElse(null);
                        if (f != null) {
                            f.setLabel(isAnomaly ? "ANOMALY" : "NORMAL");
                            f.setAnomalyScore(score);
                        }
                        if (isAnomaly) {
                            Map<String, Object> explanation = (Map<String, Object>) pred.get("explanation");
                            String reason = "High anomaly score";
                            if (explanation != null && !explanation.isEmpty()) {
                                String topFeature = explanation.keySet().iterator().next();
                                reason = "Abnormal " + topFeature;
                            }
                            
                            final String finalReason = reason;
                            
                            nodeRepository.findById(nodeId).ifPresent(node -> {
                                node.setStatus(com.fedanomaly.backend.model.NodeStatus.SUSPICIOUS);
                                nodeRepository.save(node);
                                
                                Map<String, Object> event = new HashMap<>();
                                event.put("type", "ANOMALY_DETECTED");
                                event.put("node", nodeId);
                                event.put("message", "ML model flagged traffic: " + finalReason);
                                if (explanation != null) {
                                    event.put("explanation", explanation);
                                }
                                messagingTemplate.convertAndSend("/topic/events", event);
                            });
                        }
                    }
                }
            } catch (Exception e) {
                // Ignore if ML service is down or model not trained yet
            }
        }
        
        flowRepository.saveAll(flowsToSave);
        
        // Broadcast flows
        for (NetworkFlow f : flowsToSave) {
            messagingTemplate.convertAndSend("/topic/flows", f);
        }
    }
}
