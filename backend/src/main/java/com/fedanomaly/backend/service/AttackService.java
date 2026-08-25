package com.fedanomaly.backend.service;
import org.springframework.stereotype.Service;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

@Service
public class AttackService {
    private final Map<String, String> activeAttacks = new ConcurrentHashMap<>();

    public void injectAttack(String targetNode, String attackType) {
        activeAttacks.put(targetNode, attackType);
    }
    
    public void stopAttack(String targetNode) {
        activeAttacks.remove(targetNode);
    }
    
    public void stopAll() {
        activeAttacks.clear();
    }

    public Map<String, String> getActiveAttacks() {
        return activeAttacks;
    }

    public void injectAttack() {
        // Fallback or demo behavior if called without args
        activeAttacks.put("node-1", "DDOS");
    }
}
