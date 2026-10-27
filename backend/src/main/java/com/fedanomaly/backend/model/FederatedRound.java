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

    // Global model artifact this round produced
    private String modelChecksum;

    // Global model accuracy: computed once by the ML service on the fixed, versioned evaluation
    // dataset right after this version was deployed. Never changes afterwards.
    private Double globalAccuracy;
    private Double globalPrecision;
    private Double globalRecall;
    private Double globalF1;
    private String evalDatasetId;
    private Long evalDatasetSize;

    // Separate live metric: confusion counts of the predictions this version made on simulated
    // traffic since it was deployed (ground truth = simulator attack labels). Grows with traffic.
    // Column names kept from the previous schema. Nullable: older rows keep working.
    private Long evalTp;
    private Long evalFp;
    private Long evalFn;
    private Long evalTn;

    /** Below this many live predictions the live metric is reported as "not enough data yet". */
    public static final long MIN_LIVE_SAMPLES = 30;
    
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
    public Double getGlobalAccuracy() { return globalAccuracy; }
    public Double getGlobalPrecision() { return globalPrecision; }
    public Double getGlobalRecall() { return globalRecall; }
    public Double getGlobalF1() { return globalF1; }
    public String getEvalDatasetId() { return evalDatasetId; }
    public Long getEvalDatasetSize() { return evalDatasetSize; }
    /** Set once, when the version is created, from the ML service's fixed-dataset evaluation. */
    public void setGlobalEvaluation(Double accuracy, Double precision, Double recall, Double f1,
                                    String datasetId, Long datasetSize) {
        globalAccuracy = accuracy; globalPrecision = precision; globalRecall = recall; globalF1 = f1;
        evalDatasetId = datasetId; evalDatasetSize = datasetSize;
        accuracyAfter = accuracy != null ? accuracy : 0.0;
    }

    public long[] liveCounts() {
        return new long[]{ n(evalTp), n(evalFp), n(evalFn), n(evalTn) };
    }
    public void setLiveCounts(long[] c) {
        evalTp = c[0]; evalFp = c[1]; evalFn = c[2]; evalTn = c[3];
    }
    public long getLiveSamples() { return n(evalTp) + n(evalFp) + n(evalFn) + n(evalTn); }
    /** Live accuracy of this version on simulated traffic, or null with too few predictions. */
    public Double getLiveAccuracy() {
        long total = getLiveSamples();
        if (total < MIN_LIVE_SAMPLES) return null;
        return Math.round((double) (n(evalTp) + n(evalTn)) / total * 1000.0) / 10.0;
    }
    private static long n(Long v) { return v == null ? 0 : v; }
}
