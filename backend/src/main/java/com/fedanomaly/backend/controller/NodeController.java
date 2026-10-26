package com.fedanomaly.backend.controller;

import com.fedanomaly.backend.model.NetworkNode;
import com.fedanomaly.backend.model.NodeStatus;
import com.fedanomaly.backend.repository.NetworkNodeRepository;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.messaging.simp.SimpMessagingTemplate;

@RestController
@RequestMapping("/api/nodes")
public class NodeController {
    
    private final NetworkNodeRepository nodeRepository;
    private final SimpMessagingTemplate messagingTemplate;
    
    public NodeController(NetworkNodeRepository nodeRepository, SimpMessagingTemplate messagingTemplate) {
        this.nodeRepository = nodeRepository;
        this.messagingTemplate = messagingTemplate;
    }
    
    @GetMapping
    public List<NetworkNode> getNodes() { 
        return nodeRepository.findAll(); 
    }
    
    @PutMapping("/{id}/position")
    public void updatePosition(@PathVariable String id, @RequestParam double x, @RequestParam double y) {
        Optional<NetworkNode> nodeOpt = nodeRepository.findById(id);
        if (nodeOpt.isPresent()) {
            NetworkNode node = nodeOpt.get();
            node.setPosX(x);
            node.setPosY(y);
            nodeRepository.save(node);
        }
    }
    
    @PutMapping("/{id}/recover")
    public void recoverNode(@PathVariable String id) {
        nodeRepository.findById(id).ifPresent(node -> {
            if (startRecovery(node)) {
                nodeRepository.save(node);
                messagingTemplate.convertAndSend("/topic/nodes", nodeRepository.findAll());
            }
        });
    }

    @PutMapping("/recoverAll")
    public void recoverAllNodes() {
        List<NetworkNode> nodes = nodeRepository.findAll();
        boolean changed = false;
        for (NetworkNode node : nodes) {
            changed |= startRecovery(node);
        }
        if (changed) {
            nodeRepository.saveAll(nodes);
            messagingTemplate.convertAndSend("/topic/nodes", nodes);
        }
    }

    /**
     * Release a quarantined/suspicious node into probation (RECOVERING).
     * Trust is NOT reset: it must be re-earned through clean traffic (see TrustService).
     */
    private boolean startRecovery(NetworkNode node) {
        if (node.getStatus() != NodeStatus.QUARANTINED && node.getStatus() != NodeStatus.SUSPICIOUS) {
            return false;
        }
        String from = node.getStatus().name();
        node.setStatus(NodeStatus.RECOVERING);

        Map<String, Object> event = new HashMap<>();
        event.put("type", "RECOVERY_STARTED");
        event.put("node", node.getId());
        event.put("from", from);
        event.put("trustScore", node.getTrustScore());
        event.put("timestamp", Instant.now().toString());
        event.put("message", node.getName() + " released from " + from + " into probation (trust "
            + String.format("%.1f", node.getTrustScore()) + ")");
        messagingTemplate.convertAndSend("/topic/events", event);
        return true;
    }
}
