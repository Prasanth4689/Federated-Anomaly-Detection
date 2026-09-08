package com.fedanomaly.backend.service;

import org.springframework.stereotype.Service;
import com.fedanomaly.backend.model.NetworkNode;
import com.fedanomaly.backend.model.NodeStatus;
import com.fedanomaly.backend.repository.NetworkNodeRepository;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.time.Instant;

@Service
public class TrustService {
    private final NetworkNodeRepository nodeRepository;
    private final SimpMessagingTemplate messagingTemplate;

    public TrustService(NetworkNodeRepository nodeRepository, SimpMessagingTemplate messagingTemplate) {
        this.nodeRepository = nodeRepository;
        this.messagingTemplate = messagingTemplate;
    }

    public void updateTrustScores() {
        List<NetworkNode> nodes = nodeRepository.findAll();
        boolean changed = false;
        
        for (NetworkNode node : nodes) {
            double oldTrust = node.getTrustScore();
            String reason = null;

            if (node.getStatus() == NodeStatus.SUSPICIOUS) {
                // Trust decay for suspicious nodes — proportional to how suspicious
                double decay = 2.5;
                node.setTrustScore(Math.max(0, node.getTrustScore() - decay));
                reason = "Anomalous traffic detected — sustained suspicious behavior";

                if (node.getTrustScore() < 30) {
                    node.setStatus(NodeStatus.QUARANTINED);
                    reason = "Trust below quarantine threshold (30) — node isolated";

                    // Emit mitigation event
                    Map<String, Object> mitigationEvent = new HashMap<>();
                    mitigationEvent.put("type", "MITIGATION");
                    mitigationEvent.put("node", node.getId());
                    mitigationEvent.put("action", "QUARANTINE");
                    mitigationEvent.put("trustScore", node.getTrustScore());
                    mitigationEvent.put("timestamp", Instant.now().toString());
                    mitigationEvent.put("message", node.getName() + " quarantined — trust " + 
                        String.format("%.1f", node.getTrustScore()) + " < 30 threshold");
                    messagingTemplate.convertAndSend("/topic/events", mitigationEvent);
                }
                changed = true;
            } else if (node.getStatus() == NodeStatus.HEALTHY && node.getTrustScore() < 100) {
                // Gradual trust recovery through sustained normal behavior
                node.setTrustScore(Math.min(100, node.getTrustScore() + 1.0));
                if (node.getTrustScore() - oldTrust >= 5.0 || node.getTrustScore() >= 100) {
                    reason = "Sustained normal behavior — trust recovering";
                }
                changed = true;
            }

            // Emit trust change event for significant changes
            double delta = node.getTrustScore() - oldTrust;
            if (reason != null && Math.abs(delta) >= 2.0) {
                Map<String, Object> event = new HashMap<>();
                event.put("type", "TRUST_CHANGE");
                event.put("node", node.getId());
                event.put("oldTrust", Math.round(oldTrust * 10.0) / 10.0);
                event.put("newTrust", Math.round(node.getTrustScore() * 10.0) / 10.0);
                event.put("delta", Math.round(delta * 10.0) / 10.0);
                event.put("reason", reason);
                event.put("status", node.getStatus().name());
                event.put("timestamp", Instant.now().toString());
                event.put("message", node.getName() + " trust " + 
                    String.format("%.1f", oldTrust) + " → " + 
                    String.format("%.1f", node.getTrustScore()) + ": " + reason);
                messagingTemplate.convertAndSend("/topic/events", event);
            }
        }
        
        if (changed) {
            nodeRepository.saveAll(nodes);
            messagingTemplate.convertAndSend("/topic/nodes", nodes);
        }
    }
}
