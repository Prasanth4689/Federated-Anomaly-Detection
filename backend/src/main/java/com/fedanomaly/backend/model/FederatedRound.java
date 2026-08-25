package com.fedanomaly.backend.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.Instant;
import java.util.UUID;

@Entity
public class FederatedRound {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    private UUID simulationId;
    private int roundNumber;
    private Instant startedAt;
    private Instant completedAt;
    private int participants;
    private String modelVersion;
    private double accuracyBefore;
    private double accuracyAfter;
    
    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public UUID getSimulationId() { return simulationId; }
    public void setSimulationId(UUID simulationId) { this.simulationId = simulationId; }
    public int getRoundNumber() { return roundNumber; }
    public void setRoundNumber(int roundNumber) { this.roundNumber = roundNumber; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
    public int getParticipants() { return participants; }
    public void setParticipants(int participants) { this.participants = participants; }
    public String getModelVersion() { return modelVersion; }
    public void setModelVersion(String modelVersion) { this.modelVersion = modelVersion; }
    public double getAccuracyBefore() { return accuracyBefore; }
    public void setAccuracyBefore(double accuracyBefore) { this.accuracyBefore = accuracyBefore; }
    public double getAccuracyAfter() { return accuracyAfter; }
    public void setAccuracyAfter(double accuracyAfter) { this.accuracyAfter = accuracyAfter; }
}
