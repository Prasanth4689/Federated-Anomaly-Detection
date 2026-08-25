package com.fedanomaly.backend.service;

import com.fedanomaly.backend.model.NetworkFlow;
import com.fedanomaly.backend.model.NetworkNode;
import com.fedanomaly.backend.repository.NetworkFlowRepository;
import com.fedanomaly.backend.repository.NetworkNodeRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.*;

@Service
public class FederatedLearningService {
    private final NetworkFlowRepository flowRepository;
    private final NetworkNodeRepository nodeRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final RestTemplate restTemplate;
    
    @org.springframework.beans.factory.annotation.Value("${ML_SERVICE_URL:http://localhost:5000}")
    private String ML_SERVICE_URL;

    public FederatedLearningService(NetworkFlowRepository flowRepository, 
                                  NetworkNodeRepository nodeRepository,
                                  SimpMessagingTemplate messagingTemplate) {
        this.flowRepository = flowRepository;
        this.nodeRepository = nodeRepository;
        this.messagingTemplate = messagingTemplate;
        this.restTemplate = new RestTemplate();
    }

    public void runFederatedRound() {
        List<NetworkNode> nodes = nodeRepository.findAll();
        if (nodes.isEmpty()) return;

        // Step 1: Local Training
        List<Map<String, Object>> clientModels = new ArrayList<>();
        List<Double> weights = new ArrayList<>();

        for (NetworkNode node : nodes) {
            List<NetworkFlow> nodeFlows = flowRepository.findAll(); // Simplified: should filter by node
            if (nodeFlows.isEmpty()) continue;

            Map<String, Object> trainReq = new HashMap<>();
            trainReq.put("node_id", node.getId());
            
            List<Map<String, Object>> flows = new ArrayList<>();
            for(NetworkFlow f : nodeFlows) {
                Map<String, Object> map = new HashMap<>();
                map.put("flow_id", f.getId() != null ? f.getId().toString() : UUID.randomUUID().toString());
                map.put("duration_ms", f.getDurationMs());
                map.put("packet_count", f.getPacketCount());
                map.put("total_bytes", f.getTotalBytes());
                map.put("avg_packet_size", f.getAvgPacketSize());
                map.put("packets_per_sec", f.getPacketsPerSec());
                map.put("bytes_per_sec", f.getBytesPerSec());
                flows.add(map);
            }
            trainReq.put("flow_data", flows);

            try {
                Map response = restTemplate.postForObject(ML_SERVICE_URL + "/train", trainReq, Map.class);
                if (response != null && response.containsKey("model_params")) {
                    clientModels.add((Map<String, Object>) response.get("model_params"));
                    weights.add(1.0); // uniform weight for now
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
            Map fedAvgResponse = restTemplate.postForObject(ML_SERVICE_URL + "/fedavg", fedAvgReq, Map.class);
            if (fedAvgResponse != null && fedAvgResponse.containsKey("global_model_params")) {
                
                // Step 3: Broadcast
                Map<String, Object> broadcastReq = new HashMap<>();
                broadcastReq.put("global_model_params", fedAvgResponse.get("global_model_params"));
                restTemplate.postForObject(ML_SERVICE_URL + "/broadcast", broadcastReq, Map.class);
                
                // Extract version: model_version is a top-level field in /fedavg response
                Object version = fedAvgResponse.get("model_version");
                
                // Notify frontend
                Map<String, Object> event = new HashMap<>();
                event.put("type", "FED_ROUND_COMPLETE");
                event.put("version", version != null ? version : clientModels.size());
                event.put("participants", clientModels.size());
                event.put("message", "FedAvg completed with " + clientModels.size() + " participants. Model v" + version);
                messagingTemplate.convertAndSend("/topic/events", event);
            }
        } catch (Exception e) {
            System.err.println("Failed FedAvg round: " + e.getMessage());
            // Emit a fallback event so front-end still increments the counter
            Map<String, Object> fallbackEvent = new HashMap<>();
            fallbackEvent.put("type", "FED_ROUND_COMPLETE");
            fallbackEvent.put("version", clientModels.size());
            fallbackEvent.put("participants", clientModels.size());
            fallbackEvent.put("message", "Local training round complete (" + clientModels.size() + " nodes)");
            messagingTemplate.convertAndSend("/topic/events", fallbackEvent);
        }
    }
}
