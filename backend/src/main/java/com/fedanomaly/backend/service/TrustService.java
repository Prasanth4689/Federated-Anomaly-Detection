package com.fedanomaly.backend.service;

import org.springframework.stereotype.Service;
import com.fedanomaly.backend.model.NetworkNode;
import com.fedanomaly.backend.repository.NetworkNodeRepository;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import java.util.List;

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
            if (node.getStatus() == com.fedanomaly.backend.model.NodeStatus.SUSPICIOUS) {
                node.setTrustScore(Math.max(0, node.getTrustScore() - 2.5));
                if (node.getTrustScore() < 30) {
                    node.setStatus(com.fedanomaly.backend.model.NodeStatus.QUARANTINED);
                }
                changed = true;
            } else if (node.getStatus() == com.fedanomaly.backend.model.NodeStatus.HEALTHY && node.getTrustScore() < 100) {
                node.setTrustScore(Math.min(100, node.getTrustScore() + 1.0));
                changed = true;
            }
        }
        
        if (changed) {
            nodeRepository.saveAll(nodes);
            messagingTemplate.convertAndSend("/topic/nodes", nodes);
        }
    }
}
