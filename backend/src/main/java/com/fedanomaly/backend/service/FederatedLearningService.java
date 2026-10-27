package com.fedanomaly.backend.service;

import com.fedanomaly.backend.model.FederatedRound;
import com.fedanomaly.backend.model.NetworkFlow;
import com.fedanomaly.backend.model.NetworkNode;
import com.fedanomaly.backend.model.NodeStatus;
import com.fedanomaly.backend.repository.FederatedRoundRepository;
import com.fedanomaly.backend.repository.NetworkFlowRepository;
import com.fedanomaly.backend.repository.NetworkNodeRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class FederatedLearningService {
    private final NetworkFlowRepository flowRepository;
    private final NetworkNodeRepository nodeRepository;
    private final FederatedRoundRepository roundRepository;
    private final FlowService flowService;
    private final SimpMessagingTemplate messagingTemplate;
    private final RestTemplate restTemplate;
    
    @org.springframework.beans.factory.annotation.Value("${ML_SERVICE_URL:http://localhost:5000}")
    private String ML_SERVICE_URL;

    public FederatedLearningService(NetworkFlowRepository flowRepository, 
                                  NetworkNodeRepository nodeRepository,
                                  FederatedRoundRepository roundRepository,
                                  FlowService flowService,
                                  SimpMessagingTemplate messagingTemplate) {
        this.flowRepository = flowRepository;
        this.nodeRepository = nodeRepository;
        this.roundRepository = roundRepository;
        this.flowService = flowService;
        this.messagingTemplate = messagingTemplate;
        this.restTemplate = MlHttp.restTemplate(30_000);
    }

    public void runFederatedRound() {
        List<NetworkNode> nodes = nodeRepository.findAll();
        if (nodes.isEmpty()) return;

        // Step 1: Local Training
        List<Map<String, Object>> clientModels = new ArrayList<>();
        List<Double> weights = new ArrayList<>();

        // Fetch all flows once and filter per node
        List<NetworkFlow> allFlows = flowRepository.findAll();

        for (NetworkNode node : nodes) {
            // Skip quarantined nodes (do not include compromised models)
            if (node.getStatus() == NodeStatus.QUARANTINED) continue;

            // Only use flows where this node was involved, and only those the current model
            // judged normal: keeps detected attack floods from being learned as "normal".
            // (Uses the model's own prediction label, never simulated ground truth.)
            List<NetworkFlow> nodeFlows = allFlows.stream()
                .filter(f -> f.getSourceNode().equals(node.getId()) || f.getDestNode().equals(node.getId()))
                .filter(f -> !"ANOMALY".equals(f.getLabel()))
                .collect(Collectors.toList());
                
            if (nodeFlows.isEmpty()) continue;

            Map<String, Object> trainReq = new HashMap<>();
            trainReq.put("node_id", node.getId());
            
            List<Map<String, Object>> flows = new ArrayList<>();
            for(NetworkFlow f : nodeFlows) {
                Map<String, Object> map = new HashMap<>();
                map.put("flow_id", f.getFlowId() != null ? f.getFlowId().toString() : String.valueOf(f.getId()));
                map.put("duration_ms", f.getDurationMs());
                map.put("packet_count", f.getPacketCount());
                map.put("total_bytes", f.getTotalBytes());
                map.put("avg_packet_size", f.getAvgPacketSize());
                map.put("packets_per_sec", f.getPacketsPerSec());
                map.put("bytes_per_sec", f.getBytesPerSec());
                map.put("dest_port", f.getDestPort()); // Ensure port_diversity feature works
                flows.add(map);
            }
            trainReq.put("flow_data", flows);

            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> response = restTemplate.postForObject(ML_SERVICE_URL + "/train", trainReq, Map.class);
                if (response != null && response.containsKey("model_params")) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> modelParams = (Map<String, Object>) response.get("model_params");
                    // Empty model check
                    if (!modelParams.isEmpty()) {
                        clientModels.add(modelParams);
                        // Valid aggregation metric: trust score combined with sample proportion
                        double trustFactor = Math.max(0.01, node.getTrustScore() / 100.0);
                        double sampleFactor = allFlows.isEmpty() ? 1.0 : (double) nodeFlows.size() / allFlows.size();
                        weights.add(trustFactor * Math.max(0.1, sampleFactor));
                    }
                }
            } catch (Exception e) {
                System.err.println("Failed to train node " + node.getId() + ": " + e.getMessage());
            }
        }

        // Step 2: FedAvg
        if (clientModels.isEmpty()) return;

        Map<String, Object> fedAvgReq = new HashMap<>();
        fedAvgReq.put("client_models", clientModels);
        fedAvgReq.put("weights", weights);

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> fedAvgResponse = restTemplate.postForObject(ML_SERVICE_URL + "/fedavg", fedAvgReq, Map.class);
            if (fedAvgResponse != null && fedAvgResponse.containsKey("global_model_params")) {
                
                // Step 3: Broadcast
                Map<String, Object> broadcastReq = new HashMap<>();
                broadcastReq.put("global_model_params", fedAvgResponse.get("global_model_params"));
                restTemplate.postForObject(ML_SERVICE_URL + "/broadcast", broadcastReq, Map.class);
                
                // Extract version: model_version is a top-level field in /fedavg response
                Object versionObj = fedAvgResponse.get("model_version");
                int version = versionObj instanceof Number ? ((Number) versionObj).intValue() : (int) roundRepository.count() + 1;
                
                // Genuine evaluation metrics computed from actual model predictions
                Map<String, Double> metrics = flowService.getEvaluationMetrics();
                double accuracy = metrics.getOrDefault("accuracy", 0.0);

                // Persist round record in database
                FederatedRound round = new FederatedRound();
                round.setRoundNumber(version);
                round.setModelVersion("v" + version + ".0");
                round.setParticipants(clientModels.size());
                round.setAccuracyAfter(accuracy);
                round.setCompletedAt(Instant.now());
                roundRepository.save(round);
                
                // Notify frontend
                Map<String, Object> event = new HashMap<>();
                event.put("type", "FED_ROUND_COMPLETE");
                event.put("version", version);
                event.put("participants", clientModels.size());
                event.put("accuracy", accuracy);
                event.put("precision", metrics.get("precision"));
                event.put("recall", metrics.get("recall"));
                event.put("f1", metrics.get("f1"));
                event.put("samples", metrics.get("samples"));
                event.put("message", "FedAvg completed with " + clientModels.size() + " participants. Model v" + version + ".0");
                messagingTemplate.convertAndSend("/topic/events", event);
            }
        } catch (Exception e) {
            System.err.println("Failed FedAvg round: " + e.getMessage());
            // Report the failure; do not present it as a completed round
            Map<String, Object> failEvent = new HashMap<>();
            failEvent.put("type", "FED_ROUND_FAILED");
            failEvent.put("participants", clientModels.size());
            failEvent.put("timestamp", Instant.now().toString());
            failEvent.put("message", "FedAvg aggregation failed (" + clientModels.size() + " local models trained): " + e.getMessage());
            messagingTemplate.convertAndSend("/topic/events", failEvent);
        }
    }
}
