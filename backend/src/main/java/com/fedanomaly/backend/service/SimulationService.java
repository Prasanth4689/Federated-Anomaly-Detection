package com.fedanomaly.backend.service;

import org.springframework.stereotype.Service;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PreDestroy;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import com.fedanomaly.backend.repository.NetworkFlowRepository;

import java.util.HashMap;
import java.util.Map;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
public class SimulationService {
    private ScheduledExecutorService executorService;
    private final TrafficGeneratorService trafficGeneratorService;
    private final FlowService flowService;
    private final TrustService trustService;
    private final FederatedLearningService fedLearningService;
    private final NetworkFlowRepository flowRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private int tickCount = 0;
    private volatile String state = "STOPPED"; // RUNNING, PAUSED, STOPPED
    private volatile int speedMultiplier = 1; // 1x, 2x, 4x

    public SimulationService(TrafficGeneratorService trafficGeneratorService, 
                             FlowService flowService, 
                             TrustService trustService,
                             FederatedLearningService fedLearningService,
                             NetworkFlowRepository flowRepository,
                             SimpMessagingTemplate messagingTemplate) {
        this.trafficGeneratorService = trafficGeneratorService;
        this.flowService = flowService;
        this.trustService = trustService;
        this.fedLearningService = fedLearningService;
        this.flowRepository = flowRepository;
        this.messagingTemplate = messagingTemplate;
    }

    public void startSimulation() {
        if ("RUNNING".equals(state)) return;
        state = "RUNNING";
        executorService = Executors.newSingleThreadScheduledExecutor();
        long interval = 500 / speedMultiplier;
        executorService.scheduleAtFixedRate(this::tick, 0, interval, TimeUnit.MILLISECONDS);
        broadcastState();
    }

    public void pauseSimulation() {
        if (executorService != null) {
            executorService.shutdown();
        }
        state = "PAUSED";
        broadcastState();
    }

    public void stopSimulation() {
        if (executorService != null) {
            executorService.shutdownNow();
        }
        state = "STOPPED";
        tickCount = 0;
        broadcastState();
    }

    public void setSpeed(int multiplier) {
        this.speedMultiplier = multiplier;
        if ("RUNNING".equals(state)) {
            // Restart with new speed
            if (executorService != null) executorService.shutdownNow();
            executorService = Executors.newSingleThreadScheduledExecutor();
            long interval = 500 / speedMultiplier;
            executorService.scheduleAtFixedRate(this::tick, 0, interval, TimeUnit.MILLISECONDS);
        }
        broadcastState();
    }

    public Map<String, Object> getSimulationState() {
        Map<String, Object> s = new HashMap<>();
        s.put("state", state);
        s.put("tickCount", tickCount);
        s.put("speed", speedMultiplier);
        return s;
    }

    private void tick() {
        try {
            trafficGeneratorService.generateTraffic();
            flowService.processFlows();
            trustService.updateTrustScores();
            
            tickCount++;
            
            // Clean old flows every 50 ticks to prevent unbounded H2 DB growth
            if (tickCount % 50 == 0) {
                cleanOldFlows();
            }

            // Run FedAvg round every 20 ticks (10 seconds at 1x)
            if (tickCount % 20 == 0) {
                fedLearningService.runFederatedRound();
            }
        } catch (Exception e) {
            System.err.println("Simulation tick error: " + e.getMessage());
        }
    }
    
    private void cleanOldFlows() {
        Instant cutoff = Instant.now().minus(5, ChronoUnit.MINUTES);
        // Using JPA derived query or custom query would be better, but we can do it in memory for now
        // if we don't want to modify the repository interface.
        // For efficiency, it's best to delete all in repo but we'll fetch and delete old ones
        var allFlows = flowRepository.findAll();
        var oldFlows = allFlows.stream()
            .filter(f -> f.getTimestamp().isBefore(cutoff))
            .toList();
        if (!oldFlows.isEmpty()) {
            flowRepository.deleteAll(oldFlows);
        }
    }

    private void broadcastState() {
        messagingTemplate.convertAndSend("/topic/simulation", getSimulationState());
    }
    
    @PreDestroy
    public void cleanup() {
        if (executorService != null) {
            executorService.shutdownNow();
        }
    }
}
