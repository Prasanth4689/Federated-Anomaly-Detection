package com.fedanomaly.backend.service;

import org.springframework.stereotype.Service;
import com.fedanomaly.backend.model.NetworkNode;
import com.fedanomaly.backend.model.NodeStatus;
import com.fedanomaly.backend.repository.NetworkNodeRepository;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Instant;

@Service
public class TrustService {
    private final NetworkNodeRepository nodeRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final AttackService attackService;
    private final FlowService flowService;

    // Track consecutive ticks without anomalies for suspicious node recovery
    private final Map<String, Integer> cleanTickCounts = new ConcurrentHashMap<>();

    // RECOVERING -> HEALTHY once trust has been re-earned to this level
    static final double RECOVERY_TRUST_THRESHOLD = 60.0;

    public TrustService(NetworkNodeRepository nodeRepository, SimpMessagingTemplate messagingTemplate, 
                        AttackService attackService, FlowService flowService) {
        this.nodeRepository = nodeRepository;
        this.messagingTemplate = messagingTemplate;
        this.attackService = attackService;
        this.flowService = flowService;
    }

    private Map<String, String> getActiveAttacks() {
        return attackService.getActiveAttacks();
    }

    public void updateTrustScores() {
        List<NetworkNode> nodes = nodeRepository.findAll();
        boolean changed = false;
        
        Map<String, String> activeAttacks = getActiveAttacks();
        Set<String> suspiciousThisTick = flowService.getAndClearActiveSuspiciousSources();
        
        for (NetworkNode node : nodes) {
            double oldTrust = node.getTrustScore();
            NodeStatus oldStatus = node.getStatus();
            String reason = null;

            boolean isTargetOfAttack = activeAttacks.containsKey(node.getId());

            // 1. Maintain health/threat state separate from trust score
            if (isTargetOfAttack && node.getStatus() != NodeStatus.QUARANTINED && node.getStatus() != NodeStatus.SUSPICIOUS
                    && node.getStatus() != NodeStatus.RECOVERING) {
                if (node.getStatus() != NodeStatus.UNDER_ATTACK) {
                    node.setStatus(NodeStatus.UNDER_ATTACK);
                    changed = true;
                }
            } else if (!isTargetOfAttack && node.getStatus() == NodeStatus.UNDER_ATTACK) {
                node.setStatus(NodeStatus.HEALTHY);
                changed = true;
            }

            // 2. Incident Response State Machine: Suspicious behavior handling
            if (node.getStatus() == NodeStatus.SUSPICIOUS) {
                if (suspiciousThisTick.contains(node.getId())) {
                    cleanTickCounts.put(node.getId(), 0);
                    double decay = 2.5; // Deterministic trust decay for confirmed anomalous traffic
                    node.setTrustScore(Math.max(0.0, node.getTrustScore() - decay));
                    reason = "Anomalous traffic generation detected — sustained suspicious behavior";

                    // Transition to Quarantined
                    if (node.getTrustScore() < 30.0) {
                        node.setStatus(NodeStatus.QUARANTINED);
                        reason = "Trust below threshold (30.0) — node quarantined from network";

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
                } else {
                    // Node is suspicious but no anomalies occurred this tick -> start recovery sequence
                    int cleanTicks = cleanTickCounts.compute(node.getId(), (k, v) -> v == null ? 1 : v + 1);
                    if (cleanTicks >= 3) {
                        cleanTickCounts.remove(node.getId());
                        if (node.getTrustScore() < RECOVERY_TRUST_THRESHOLD) {
                            // Low trust: anomalies stopped, but trust must still be re-earned on probation
                            node.setStatus(NodeStatus.RECOVERING);
                            reason = "No anomalies for consecutive windows — trust low, entering probation";
                        } else {
                            node.setStatus(NodeStatus.HEALTHY);
                            reason = "No anomalies detected for consecutive windows — returning to Healthy";
                        }
                        changed = true;
                    }
                }

            } else if (node.getStatus() == NodeStatus.RECOVERING) {
                // Probation after release from quarantine: no anomalies were attributed to this
                // node this tick (otherwise FlowService would have moved it to SUSPICIOUS).
                node.setTrustScore(Math.min(100.0, node.getTrustScore() + 1.0));
                changed = true;
                if (node.getTrustScore() >= RECOVERY_TRUST_THRESHOLD) {
                    node.setStatus(NodeStatus.HEALTHY);
                    reason = "Probation complete — trust re-earned through clean traffic, returning to Healthy";
                }
            } else if (node.getStatus() == NodeStatus.HEALTHY && node.getTrustScore() < 100.0) {
                // Deterministic trust recovery tied to verifiable sustained normal behavior
                node.setTrustScore(Math.min(100.0, node.getTrustScore() + 1.0));
                if (node.getTrustScore() - oldTrust >= 5.0 || node.getTrustScore() >= 100.0) {
                    reason = "Sustained normal behavior — trust recovering";
                }
                changed = true;
            }

            // Emit trust change event for significant changes
            double delta = node.getTrustScore() - oldTrust;
            if (reason != null && (Math.abs(delta) >= 2.0 || node.getStatus() != oldStatus)) {
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
