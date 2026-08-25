package com.fedanomaly.backend.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.Instant;
import java.util.UUID;

@Entity
public class NetworkFlow {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    private UUID simulationId;
    private UUID flowId;
    private Instant timestamp;
    
    private String sourceNode;
    private String destNode;
    
    private String protocol;
    private int sourcePort;
    private int destPort;
    
    private long durationMs;
    private long packetCount;
    private long totalBytes;
    private double avgPacketSize;
    private double packetsPerSec;
    private double bytesPerSec;
    
    private String connectionStatus;
    private boolean isExternal;
    
    private String label; // NORMAL/ATTACK
    private double anomalyScore;

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public UUID getSimulationId() { return simulationId; }
    public void setSimulationId(UUID simulationId) { this.simulationId = simulationId; }
    public UUID getFlowId() { return flowId; }
    public void setFlowId(UUID flowId) { this.flowId = flowId; }
    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
    public String getSourceNode() { return sourceNode; }
    public void setSourceNode(String sourceNode) { this.sourceNode = sourceNode; }
    public String getDestNode() { return destNode; }
    public void setDestNode(String destNode) { this.destNode = destNode; }
    public String getProtocol() { return protocol; }
    public void setProtocol(String protocol) { this.protocol = protocol; }
    public int getSourcePort() { return sourcePort; }
    public void setSourcePort(int sourcePort) { this.sourcePort = sourcePort; }
    public int getDestPort() { return destPort; }
    public void setDestPort(int destPort) { this.destPort = destPort; }
    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }
    public long getPacketCount() { return packetCount; }
    public void setPacketCount(long packetCount) { this.packetCount = packetCount; }
    public long getTotalBytes() { return totalBytes; }
    public void setTotalBytes(long totalBytes) { this.totalBytes = totalBytes; }
    public double getAvgPacketSize() { return avgPacketSize; }
    public void setAvgPacketSize(double avgPacketSize) { this.avgPacketSize = avgPacketSize; }
    public double getPacketsPerSec() { return packetsPerSec; }
    public void setPacketsPerSec(double packetsPerSec) { this.packetsPerSec = packetsPerSec; }
    public double getBytesPerSec() { return bytesPerSec; }
    public void setBytesPerSec(double bytesPerSec) { this.bytesPerSec = bytesPerSec; }
    public String getConnectionStatus() { return connectionStatus; }
    public void setConnectionStatus(String connectionStatus) { this.connectionStatus = connectionStatus; }
    public boolean isExternal() { return isExternal; }
    public void setExternal(boolean external) { isExternal = external; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public double getAnomalyScore() { return anomalyScore; }
    public void setAnomalyScore(double anomalyScore) { this.anomalyScore = anomalyScore; }
}
