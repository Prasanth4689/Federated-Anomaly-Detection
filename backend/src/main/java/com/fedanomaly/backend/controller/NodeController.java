package com.fedanomaly.backend.controller;

import com.fedanomaly.backend.model.NetworkNode;
import com.fedanomaly.backend.repository.NetworkNodeRepository;
import org.springframework.web.bind.annotation.*;
import java.util.List;
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
        Optional<NetworkNode> nodeOpt = nodeRepository.findById(id);
        if (nodeOpt.isPresent()) {
            NetworkNode node = nodeOpt.get();
            node.setStatus(com.fedanomaly.backend.model.NodeStatus.HEALTHY);
            node.setTrustScore(Math.max(node.getTrustScore(), 30.0));
            nodeRepository.save(node);
            
            // Broadcast the change to the frontend immediately
            messagingTemplate.convertAndSend("/topic/nodes", nodeRepository.findAll());
        }
    }
    @PutMapping("/recoverAll")
    public void recoverAllNodes() {
        List<NetworkNode> nodes = nodeRepository.findAll();
        for (NetworkNode node : nodes) {
            node.setStatus(com.fedanomaly.backend.model.NodeStatus.HEALTHY);
            node.setTrustScore(Math.max(node.getTrustScore(), 30.0));
        }
        nodeRepository.saveAll(nodes);
        messagingTemplate.convertAndSend("/topic/nodes", nodes);
    }
}
