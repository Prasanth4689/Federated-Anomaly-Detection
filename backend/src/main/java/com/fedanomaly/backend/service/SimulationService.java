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
    private final AttackService attackService;
    private int tickCount = 0;
    private volatile String state = "STOPPED"; // RUNNING, PAUSED, STOPPED
    private volatile int speedMultiplier = 1; // 1x, 2x, 4x

    public SimulationService(TrafficGeneratorService trafficGeneratorService, 
                             FlowService flowService, 
                             TrustService trustService,
                             FederatedLearningService fedLearningService,
                             NetworkFlowRepository flowRepository,
                             SimpMessagingTemplate messagingTemplate,
                             AttackService attackService) {
        this.trafficGeneratorService = trafficGeneratorService;
        this.flowService = flowService;
        this.trustService = trustService;
        this.fedLearningService = fedLearningService;
        this.flowRepository = flowRepository;
        this.messagingTemplate = messagingTemplate;
        this.attackService = attackService;
    }

    // One loop at a time. Lifecycle calls are serialized, and a running tick is never interrupted:
    // it may be committing a federated round, and cutting it off could leave the ML service on a
    // version the backend never recorded.
    public synchronized void startSimulation() {
        if ("RUNNING".equals(state)) return;
        if ("STOPPED".equals(state)) flowService.startNewSimulation();
        haltLoop(); // a paused loop is already halted; this only guards against leftovers
        state = "RUNNING";
        startLoop();
        broadcastState();
    }

    public synchronized void pauseSimulation() {
        if (!"RUNNING".equals(state)) return;
        state = "PAUSED";            // queued ticks see this and do nothing
        haltLoop();                  // waits for an in-flight tick (and FL round) to finish
        fedLearningService.persistEvaluation();
        broadcastState();
    }

    public synchronized void stopSimulation() {
        if ("STOPPED".equals(state)) return;
        state = "STOPPED";
        haltLoop();
        attackService.stopAll();     // stopping ends the scenario; attacks do not resume on next Play
        fedLearningService.persistEvaluation();
        tickCount = 0;               // simulation clock only; model version and rounds are persisted
        broadcastState();
    }

    public synchronized void setSpeed(int multiplier) {
        this.speedMultiplier = multiplier;
        if ("RUNNING".equals(state)) {
            haltLoop();
            startLoop();
        }
        broadcastState();
    }

    private void startLoop() {
        executorService = Executors.newSingleThreadScheduledExecutor();
        long interval = 500 / speedMultiplier;
        executorService.scheduleAtFixedRate(this::tick, 0, interval, TimeUnit.MILLISECONDS);
    }

    /** Stop scheduling ticks and wait for the current one to finish (never interrupts it). */
    private void haltLoop() {
        ScheduledExecutorService ex = executorService;
        executorService = null;
        if (ex == null) return;
        ex.shutdown();
        try {
            if (!ex.awaitTermination(90, TimeUnit.SECONDS)) {
                System.err.println("Simulation tick still running after 90s; it will finish on its own");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public Map<String, Object> getSimulationState() {
        Map<String, Object> s = new HashMap<>();
        s.put("state", state);
        s.put("tickCount", tickCount);
        s.put("speed", speedMultiplier);
        return s;
    }

    private void tick() {
        if (!"RUNNING".equals(state)) return; // paused/stopped while this tick was queued
        try {
            trafficGeneratorService.generateTraffic();
            flowService.processFlows();
            trustService.updateTrustScores();
            
            tickCount++;
            
            // Clean old flows every 50 ticks to prevent unbounded H2 DB growth
            if (tickCount % 50 == 0) {
                cleanOldFlows();
            }

            // Persist the current model's live counts so a restart does not lose them
            if (tickCount % 10 == 0) {
                fedLearningService.persistEvaluation();
            }

            // Automatic federated round every 20 ticks (10 s at 1x); version/round advance only on success
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
    public synchronized void cleanup() {
        haltLoop();
        if (executorService != null) {
            executorService.shutdownNow();
        }
    }
}
