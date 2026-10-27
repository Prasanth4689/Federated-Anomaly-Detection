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

    // Global model artifact this round produced, and its own evaluation on live traffic
    // (confusion counts of predictions made by this version). Nullable: older rows keep working.
    private String modelChecksum;
    private Long evalTp;
    private Long evalFp;
    private Long evalFn;
    private Long evalTn;

    /** Below this many evaluated predictions a version is reported as "not evaluated yet". */
    public static final long MIN_EVAL_SAMPLES = 30;
    
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
    public String getModelChecksum() { return modelChecksum; }
    public void setModelChecksum(String modelChecksum) { this.modelChecksum = modelChecksum; }
    public long[] evalCounts() {
        return new long[]{ n(evalTp), n(evalFp), n(evalFn), n(evalTn) };
    }
    public void setEvalCounts(long[] c) {
        evalTp = c[0]; evalFp = c[1]; evalFn = c[2]; evalTn = c[3];
        if (getEvalAccuracy() != null) accuracyAfter = getEvalAccuracy();
    }
    public long getEvalSamples() { return n(evalTp) + n(evalFp) + n(evalFn) + n(evalTn); }
    /** Accuracy of this version's own predictions, or null if not evaluated yet. */
    public Double getEvalAccuracy() {
        long total = getEvalSamples();
        if (total < MIN_EVAL_SAMPLES) return null;
        return Math.round((double) (n(evalTp) + n(evalTn)) / total * 1000.0) / 10.0;
    }
    private static long n(Long v) { return v == null ? 0 : v; }
}
