package com.fedanomaly.backend.service;
import org.springframework.stereotype.Service;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.time.Instant;

@Service
public class AttackService {
    private final Map<String, String> activeAttacks = new ConcurrentHashMap<>();
    private final Map<String, Instant> attackStartTimes = new ConcurrentHashMap<>();
    private final SimpMessagingTemplate messagingTemplate;

    public AttackService(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    public void injectAttack(String targetNode, String attackType) {
        activeAttacks.put(targetNode, attackType);
        attackStartTimes.put(targetNode, Instant.now());

        // Broadcast attack start event
        Map<String, Object> event = new java.util.HashMap<>();
        event.put("type", "ATTACK_STARTED");
        event.put("target", targetNode);
        event.put("attackType", attackType);
        event.put("timestamp", Instant.now().toString());
        event.put("message", attackType + " attack launched against " + targetNode);
        messagingTemplate.convertAndSend("/topic/events", event);
    }
    
    public void stopAttack(String targetNode) {
        String attackType = activeAttacks.remove(targetNode);
        Instant startTime = attackStartTimes.remove(targetNode);

        // Broadcast attack stop event
        Map<String, Object> event = new java.util.HashMap<>();
        event.put("type", "ATTACK_STOPPED");
        event.put("target", targetNode);
        event.put("attackType", attackType != null ? attackType : "UNKNOWN");
        event.put("timestamp", Instant.now().toString());
        if (startTime != null) {
            long durationSec = java.time.Duration.between(startTime, Instant.now()).getSeconds();
            event.put("durationSeconds", durationSec);
            event.put("message", "Attack on " + targetNode + " stopped after " + durationSec + "s");
        } else {
            event.put("message", "Attack on " + targetNode + " stopped");
        }
        messagingTemplate.convertAndSend("/topic/events", event);
    }
    
    public void stopAll() {
        for (String target : new java.util.ArrayList<>(activeAttacks.keySet())) {
            stopAttack(target);
        }
    }

    public Map<String, String> getActiveAttacks() {
        return activeAttacks;
    }

    public Instant getAttackStartTime(String targetNode) {
        return attackStartTimes.get(targetNode);
    }

    public void injectAttack() {
        // Fallback or demo behavior if called without args
        injectAttack("node-1", "DDOS");
    }
}
