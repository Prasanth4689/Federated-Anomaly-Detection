package com.fedanomaly.backend.service;

import org.springframework.stereotype.Service;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PreDestroy;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.HashMap;
import java.util.Map;

@Service
public class SimulationService {
    private ScheduledExecutorService executorService;
    private final TrafficGeneratorService trafficGeneratorService;
    private final FlowService flowService;
    private final TrustService trustService;
    private final FederatedLearningService fedLearningService;
    private final SimpMessagingTemplate messagingTemplate;
    private int tickCount = 0;
    private volatile String state = "STOPPED"; // RUNNING, PAUSED, STOPPED
    private volatile int speedMultiplier = 1; // 1x, 2x, 4x

    public SimulationService(TrafficGeneratorService trafficGeneratorService, 
                             FlowService flowService, 
                             TrustService trustService,
                             FederatedLearningService fedLearningService,
                             SimpMessagingTemplate messagingTemplate) {
        this.trafficGeneratorService = trafficGeneratorService;
        this.flowService = flowService;
        this.trustService = trustService;
        this.fedLearningService = fedLearningService;
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
            // Run FedAvg round every 20 ticks (10 seconds at 1x)
            if (tickCount % 20 == 0) {
                fedLearningService.runFederatedRound();
            }
        } catch (Exception e) {
            System.err.println("Simulation tick error: " + e.getMessage());
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
