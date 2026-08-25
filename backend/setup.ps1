$baseDir = "D:\FedApp-SOC\backend\src\main\java\com\fedanomaly\backend"

# Directories
New-Item -ItemType Directory -Force -Path "$baseDir\model"
New-Item -ItemType Directory -Force -Path "$baseDir\repository"
New-Item -ItemType Directory -Force -Path "$baseDir\service"
New-Item -ItemType Directory -Force -Path "$baseDir\controller"
New-Item -ItemType Directory -Force -Path "$baseDir\config"

# Configs
@"
server:
  port: 8080
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/fedanomaly
    username: postgres
    password: postgres
  jpa:
    hibernate:
      ddl-auto: update
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
cors:
  allowed-origins: http://localhost:5173
"@ | Out-File -FilePath "D:\FedApp-SOC\backend\src\main\resources\application.yml" -Encoding UTF8

@"
package com.fedanomaly.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CorsConfig implements WebMvcConfigurer {
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins("http://localhost:5173")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }
}
"@ | Out-File -FilePath "$baseDir\config\CorsConfig.java" -Encoding UTF8

@"
package com.fedanomaly.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic");
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOrigins("http://localhost:5173").withSockJS();
    }
}
"@ | Out-File -FilePath "$baseDir\config\WebSocketConfig.java" -Encoding UTF8

# Models
@"
package com.fedanomaly.backend.model;

public enum NodeType {
    PC, LAPTOP, SERVER, DATABASE, ROUTER, FIREWALL, IOT_DEVICE
}
"@ | Out-File -FilePath "$baseDir\model\NodeType.java" -Encoding UTF8

@"
package com.fedanomaly.backend.model;

public enum NodeStatus {
    HEALTHY, TRAINING, SYNCHRONIZING, SUSPICIOUS, UNDER_ATTACK, QUARANTINED
}
"@ | Out-File -FilePath "$baseDir\model\NodeStatus.java" -Encoding UTF8

@"
package com.fedanomaly.backend.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

@Entity
public class NetworkNode {
    @Id
    private String id;
    private String name;
    
    @Enumerated(EnumType.STRING)
    private NodeType type;
    
    private double trustScore = 100.0;
    
    @Enumerated(EnumType.STRING)
    private NodeStatus status = NodeStatus.HEALTHY;
    
    private double posX;
    private double posY;

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public NodeType getType() { return type; }
    public void setType(NodeType type) { this.type = type; }
    public double getTrustScore() { return trustScore; }
    public void setTrustScore(double trustScore) { this.trustScore = trustScore; }
    public NodeStatus getStatus() { return status; }
    public void setStatus(NodeStatus status) { this.status = status; }
    public double getPosX() { return posX; }
    public void setPosX(double posX) { this.posX = posX; }
    public double getPosY() { return posY; }
    public void setPosY(double posY) { this.posY = posY; }
}
"@ | Out-File -FilePath "$baseDir\model\NetworkNode.java" -Encoding UTF8

@"
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
"@ | Out-File -FilePath "$baseDir\model\NetworkFlow.java" -Encoding UTF8

@"
package com.fedanomaly.backend.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.Instant;
import java.util.UUID;

@Entity
public class SimulationEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    private UUID simulationId;
    private Instant timestamp;
    private String eventType;
    private String sourceNode;
    private String targetNode;
    private String description;
    private String severity;
    
    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public UUID getSimulationId() { return simulationId; }
    public void setSimulationId(UUID simulationId) { this.simulationId = simulationId; }
    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getSourceNode() { return sourceNode; }
    public void setSourceNode(String sourceNode) { this.sourceNode = sourceNode; }
    public String getTargetNode() { return targetNode; }
    public void setTargetNode(String targetNode) { this.targetNode = targetNode; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }
}
"@ | Out-File -FilePath "$baseDir\model\SimulationEvent.java" -Encoding UTF8

@"
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
"@ | Out-File -FilePath "$baseDir\model\FederatedRound.java" -Encoding UTF8

# Repositories
@"
package com.fedanomaly.backend.repository;
import org.springframework.data.jpa.repository.JpaRepository;
import com.fedanomaly.backend.model.NetworkNode;
public interface NetworkNodeRepository extends JpaRepository<NetworkNode, String> {}
"@ | Out-File -FilePath "$baseDir\repository\NetworkNodeRepository.java" -Encoding UTF8

@"
package com.fedanomaly.backend.repository;
import org.springframework.data.jpa.repository.JpaRepository;
import com.fedanomaly.backend.model.NetworkFlow;
public interface NetworkFlowRepository extends JpaRepository<NetworkFlow, Long> {}
"@ | Out-File -FilePath "$baseDir\repository\NetworkFlowRepository.java" -Encoding UTF8

@"
package com.fedanomaly.backend.repository;
import org.springframework.data.jpa.repository.JpaRepository;
import com.fedanomaly.backend.model.SimulationEvent;
public interface SimulationEventRepository extends JpaRepository<SimulationEvent, Long> {}
"@ | Out-File -FilePath "$baseDir\repository\SimulationEventRepository.java" -Encoding UTF8

@"
package com.fedanomaly.backend.repository;
import org.springframework.data.jpa.repository.JpaRepository;
import com.fedanomaly.backend.model.FederatedRound;
public interface FederatedRoundRepository extends JpaRepository<FederatedRound, Long> {}
"@ | Out-File -FilePath "$baseDir\repository\FederatedRoundRepository.java" -Encoding UTF8

# Services
@"
package com.fedanomaly.backend.service;
import org.springframework.stereotype.Service;
@Service
public class FlowService {
    public void processFlows() {}
}
"@ | Out-File -FilePath "$baseDir\service\FlowService.java" -Encoding UTF8

@"
package com.fedanomaly.backend.service;
import org.springframework.stereotype.Service;
@Service
public class TrustService {
    public void updateTrustScores() {}
}
"@ | Out-File -FilePath "$baseDir\service\TrustService.java" -Encoding UTF8

@"
package com.fedanomaly.backend.service;
import org.springframework.stereotype.Service;
@Service
public class TrafficGeneratorService {
    public void generateTraffic() {}
}
"@ | Out-File -FilePath "$baseDir\service\TrafficGeneratorService.java" -Encoding UTF8

@"
package com.fedanomaly.backend.service;
import org.springframework.stereotype.Service;
@Service
public class AttackService {
    public void injectAttack() {}
}
"@ | Out-File -FilePath "$baseDir\service\AttackService.java" -Encoding UTF8

@"
package com.fedanomaly.backend.service;

import org.springframework.stereotype.Service;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PreDestroy;

@Service
public class SimulationService {
    private ScheduledExecutorService executorService;
    private final TrafficGeneratorService trafficGeneratorService;
    private final FlowService flowService;
    private final TrustService trustService;

    public SimulationService(TrafficGeneratorService trafficGeneratorService, FlowService flowService, TrustService trustService) {
        this.trafficGeneratorService = trafficGeneratorService;
        this.flowService = flowService;
        this.trustService = trustService;
    }

    public void startSimulation() {
        if (executorService != null && !executorService.isShutdown()) return;
        executorService = Executors.newSingleThreadScheduledExecutor();
        executorService.scheduleAtFixedRate(this::tick, 0, 500, TimeUnit.MILLISECONDS);
    }

    public void pauseSimulation() {
        if (executorService != null) {
            executorService.shutdown();
        }
    }

    public void stopSimulation() {
        if (executorService != null) {
            executorService.shutdownNow();
        }
    }

    private void tick() {
        trafficGeneratorService.generateTraffic();
        flowService.processFlows();
        trustService.updateTrustScores();
    }
    
    @PreDestroy
    public void cleanup() {
        if (executorService != null) {
            executorService.shutdownNow();
        }
    }
}
"@ | Out-File -FilePath "$baseDir\service\SimulationService.java" -Encoding UTF8

# Controllers
@"
package com.fedanomaly.backend.controller;

import org.springframework.web.bind.annotation.*;
import com.fedanomaly.backend.service.SimulationService;

@RestController
@RequestMapping("/api/simulation")
public class SimulationController {
    private final SimulationService simulationService;

    public SimulationController(SimulationService simulationService) {
        this.simulationService = simulationService;
    }

    @PostMapping("/start")
    public void start() { simulationService.startSimulation(); }

    @PostMapping("/pause")
    public void pause() { simulationService.pauseSimulation(); }

    @PostMapping("/stop")
    public void stop() { simulationService.stopSimulation(); }
    
    @GetMapping("/state")
    public String getState() { return "{}"; }
}
"@ | Out-File -FilePath "$baseDir\controller\SimulationController.java" -Encoding UTF8

@"
package com.fedanomaly.backend.controller;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/nodes")
public class NodeController {
    @GetMapping
    public String getNodes() { return "[]"; }
    @PutMapping("/{id}/position")
    public void updatePosition(@PathVariable String id) {}
    @PutMapping("/{id}/recover")
    public void recoverNode(@PathVariable String id) {}
}
"@ | Out-File -FilePath "$baseDir\controller\NodeController.java" -Encoding UTF8

@"
package com.fedanomaly.backend.controller;
import org.springframework.web.bind.annotation.*;
import com.fedanomaly.backend.service.AttackService;
@RestController
@RequestMapping("/api/attacks")
public class AttackController {
    private final AttackService attackService;
    public AttackController(AttackService attackService) { this.attackService = attackService; }
    @PostMapping("/inject")
    public void injectAttack() { attackService.injectAttack(); }
}
"@ | Out-File -FilePath "$baseDir\controller\AttackController.java" -Encoding UTF8

@"
package com.fedanomaly.backend.controller;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/flows")
public class FlowController {
    @GetMapping
    public String getFlows() { return "{}"; }
    @GetMapping("/export")
    public String exportFlows() { return ""; }
}
"@ | Out-File -FilePath "$baseDir\controller\FlowController.java" -Encoding UTF8

@"
package com.fedanomaly.backend.controller;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/federated")
public class FederatedController {
    @PostMapping("/round")
    public void triggerRound() {}
    @GetMapping("/rounds")
    public String getRounds() { return "[]"; }
}
"@ | Out-File -FilePath "$baseDir\controller\FederatedController.java" -Encoding UTF8

