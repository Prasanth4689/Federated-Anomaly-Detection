package com.fedanomaly.backend.service;

import com.fedanomaly.backend.model.FederatedRound;
import com.fedanomaly.backend.model.NetworkFlow;
import com.fedanomaly.backend.model.NetworkNode;
import com.fedanomaly.backend.model.NodeStatus;
import com.fedanomaly.backend.repository.FederatedRoundRepository;
import com.fedanomaly.backend.repository.NetworkFlowRepository;
import com.fedanomaly.backend.repository.NetworkNodeRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class FederatedLearningService {
    private final NetworkFlowRepository flowRepository;
    private final NetworkNodeRepository nodeRepository;
    private final FederatedRoundRepository roundRepository;
    private final FlowService flowService;
    private final SimpMessagingTemplate messagingTemplate;
    private final RestTemplate restTemplate;
    private final MlWarmupService mlWarmupService;
    
    @org.springframework.beans.factory.annotation.Value("${ML_SERVICE_URL:http://localhost:5000}")
    private String ML_SERVICE_URL;

    public FederatedLearningService(NetworkFlowRepository flowRepository, 
                                  NetworkNodeRepository nodeRepository,
                                  FederatedRoundRepository roundRepository,
                                  FlowService flowService,
                                  SimpMessagingTemplate messagingTemplate,
                                  MlWarmupService mlWarmupService) {
        this.flowRepository = flowRepository;
        this.nodeRepository = nodeRepository;
        this.roundRepository = roundRepository;
        this.flowService = flowService;
        this.messagingTemplate = messagingTemplate;
        this.restTemplate = MlHttp.restTemplate(30_000);
        this.mlWarmupService = mlWarmupService;
    }

    /** After a restart, keep evaluating the current model from its persisted counts. */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void restoreEvaluation() {
        roundRepository.findTopByOrderByRoundNumberDescIdDesc().ifPresent(r -> {
            long[] c = r.liveCounts();
            flowService.restoreCounts(r.getRoundNumber(), c[0], c[1], c[2], c[3]);
            System.out.println("Current global model: v" + r.getRoundNumber() + " checksum=" + r.getModelChecksum()
                + " evaluated samples=" + r.getLiveSamples());
        });
    }

    /** Save the current model version's evaluation counts (called periodically and on shutdown). */
    @jakarta.annotation.PreDestroy
    public synchronized void persistEvaluation() {
        roundRepository.findTopByOrderByRoundNumberDescIdDesc().ifPresent(r -> {
            long[] live = flowService.getCounts(r.getRoundNumber());
            if (Arrays.equals(live, r.liveCounts())) return;
            r.setLiveCounts(live);
            roundRepository.save(r);
        });
    }

    private static Double num(Map<String, Object> m, String key) {
        return m != null && m.get(key) instanceof Number v ? v.doubleValue() : null;
    }

    /**
     * The deployed global model and its two (separate) metrics:
     *  - global*: fixed-dataset accuracy stored with the version; changes only with a new version
     *  - live: this version's predictions on simulated traffic since deployment; grows with traffic
     */
    public Map<String, Object> currentModel() {
        Map<String, Object> m = new HashMap<>();
        Optional<FederatedRound> latest = roundRepository.findTopByOrderByRoundNumberDescIdDesc();
        int version = latest.map(FederatedRound::getRoundNumber).orElse(0);
        m.put("version", version);
        // Completed (successfully committed) aggregation rounds, from the persisted round records
        m.put("completedRounds", roundRepository.count());
        m.put("checksum", latest.map(FederatedRound::getModelChecksum).orElse(null));
        m.put("participants", latest.map(FederatedRound::getParticipants).orElse(0));
        m.put("completedAt", latest.map(r -> String.valueOf(r.getCompletedAt())).orElse(null));
        m.put("globalAccuracy", latest.map(FederatedRound::getGlobalAccuracy).orElse(null));
        m.put("globalPrecision", latest.map(FederatedRound::getGlobalPrecision).orElse(null));
        m.put("globalRecall", latest.map(FederatedRound::getGlobalRecall).orElse(null));
        m.put("globalF1", latest.map(FederatedRound::getGlobalF1).orElse(null));
        m.put("evalDatasetId", latest.map(FederatedRound::getEvalDatasetId).orElse(null));
        m.put("evalDatasetSize", latest.map(FederatedRound::getEvalDatasetSize).orElse(null));
        m.put("evaluated", latest.map(r -> r.getGlobalAccuracy() != null).orElse(false));

        Map<String, Double> metrics = flowService.getEvaluationMetrics(version);
        long samples = metrics.get("samples").longValue();
        boolean liveEnough = version > 0 && samples >= FederatedRound.MIN_LIVE_SAMPLES;
        Map<String, Object> live = new HashMap<>();
        live.put("window", "predictions made by v" + version + ".0 on simulated traffic since it was deployed");
        live.put("groundTruth", "simulator attack labels");
        live.put("samples", samples);
        live.put("accuracy", liveEnough ? metrics.get("accuracy") : null);
        live.put("precision", liveEnough ? metrics.get("precision") : null);
        live.put("recall", liveEnough ? metrics.get("recall") : null);
        live.put("f1", liveEnough ? metrics.get("f1") : null);
        m.put("live", live);
        Integer mlVersion = mlWarmupService.getMlModelVersion();
        m.put("mlVersion", mlVersion);
        m.put("mlChecksum", mlWarmupService.getMlModelChecksum());
        m.put("consistent", mlVersion == null ? null : (mlVersion == version
            && Objects.equals(mlWarmupService.getMlModelChecksum(), m.get("checksum"))));
        return m;
    }

    public void runFederatedRound() {
        List<NetworkNode> nodes = nodeRepository.findAll();
        if (nodes.isEmpty()) return;
        // ML host is rate-limiting us: skip this round (nothing is staged or committed) and retry next time
        if (flowService.mlBackingOff()) return;

        // Step 1: Local Training
        List<Map<String, Object>> clientModels = new ArrayList<>();
        List<Double> weights = new ArrayList<>();

        // Fetch all flows once and filter per node
        List<NetworkFlow> allFlows = flowRepository.findAll();

        for (NetworkNode node : nodes) {
            // Skip quarantined nodes (do not include compromised models)
            if (node.getStatus() == NodeStatus.QUARANTINED) continue;

            // Only use flows where this node was involved, and only those the current model
            // judged normal: keeps detected attack floods from being learned as "normal".
            // (Uses the model's own prediction label, never simulated ground truth.)
            List<NetworkFlow> nodeFlows = allFlows.stream()
                .filter(f -> f.getSourceNode().equals(node.getId()) || f.getDestNode().equals(node.getId()))
                .filter(f -> !"ANOMALY".equals(f.getLabel()))
                .collect(Collectors.toList());
                
            if (nodeFlows.isEmpty()) continue;

            Map<String, Object> trainReq = new HashMap<>();
            trainReq.put("node_id", node.getId());
            
            List<Map<String, Object>> flows = new ArrayList<>();
            for(NetworkFlow f : nodeFlows) {
                Map<String, Object> map = new HashMap<>();
                map.put("flow_id", f.getFlowId() != null ? f.getFlowId().toString() : String.valueOf(f.getId()));
                map.put("duration_ms", f.getDurationMs());
                map.put("packet_count", f.getPacketCount());
                map.put("total_bytes", f.getTotalBytes());
                map.put("avg_packet_size", f.getAvgPacketSize());
                map.put("packets_per_sec", f.getPacketsPerSec());
                map.put("bytes_per_sec", f.getBytesPerSec());
                map.put("dest_port", f.getDestPort()); // Ensure port_diversity feature works
                flows.add(map);
            }
            trainReq.put("flow_data", flows);

            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> response = restTemplate.postForObject(ML_SERVICE_URL + "/train", trainReq, Map.class);
                if (response != null && response.containsKey("model_params")) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> modelParams = (Map<String, Object>) response.get("model_params");
                    // Empty model check
                    if (!modelParams.isEmpty()) {
                        clientModels.add(modelParams);
                        // Valid aggregation metric: trust score combined with sample proportion
                        double trustFactor = Math.max(0.01, node.getTrustScore() / 100.0);
                        double sampleFactor = allFlows.isEmpty() ? 1.0 : (double) nodeFlows.size() / allFlows.size();
                        weights.add(trustFactor * Math.max(0.1, sampleFactor));
                    }
                }
            } catch (org.springframework.web.client.HttpClientErrorException.TooManyRequests e) {
                // Rate-limited mid-round: stop sending, abandon the round (candidates are only staged)
                flowService.noteRateLimited(e);
                return;
            } catch (Exception e) {
                System.err.println("Failed to train node " + node.getId() + ": " + e.getMessage());
            }
        }

        // Step 2: FedAvg
        if (clientModels.isEmpty()) return;

        int baseVersion = roundRepository.findTopByOrderByRoundNumberDescIdDesc()
            .map(FederatedRound::getRoundNumber).orElse(0);
        Map<String, Object> fedAvgReq = new HashMap<>();
        fedAvgReq.put("client_models", clientModels);
        fedAvgReq.put("weights", weights);
        fedAvgReq.put("base_version", baseVersion);

        try {
            // /fedavg aggregates, deploys the model to every node and saves it; it only
            // advances the version when that succeeds (otherwise it errors and keeps the old model)
            @SuppressWarnings("unchecked")
            Map<String, Object> fedAvgResponse = restTemplate.postForObject(ML_SERVICE_URL + "/fedavg", fedAvgReq, Map.class);
            Object versionObj = fedAvgResponse == null ? null : fedAvgResponse.get("model_version");
            if (!(versionObj instanceof Number) || ((Number) versionObj).intValue() <= baseVersion) {
                throw new IllegalStateException("ML service returned no new model version: " + versionObj);
            }
            int version = ((Number) versionObj).intValue();
            String checksum = String.valueOf(fedAvgResponse.get("model_checksum"));

            // Close the previous version's live counts before switching
            persistEvaluation();
            @SuppressWarnings("unchecked")
            Map<String, Object> ev = (Map<String, Object>) fedAvgResponse.get("evaluation");
            Double globalAccuracy = num(ev, "accuracy");

            FederatedRound round = new FederatedRound();
            round.setRoundNumber(version);
            round.setModelVersion("v" + version + ".0");
            round.setParticipants(clientModels.size());
            round.setModelChecksum(checksum);
            round.setLiveCounts(new long[4]); // no live predictions yet
            round.setGlobalEvaluation(globalAccuracy, num(ev, "precision"), num(ev, "recall"), num(ev, "f1"),
                ev == null ? null : String.valueOf(ev.get("dataset_id")),
                ev != null && ev.get("n") instanceof Number n ? n.longValue() : null);
            round.setCompletedAt(Instant.now());
            roundRepository.save(round);
            mlWarmupService.recordModel(version, checksum);

            Map<String, Object> event = new HashMap<>();
            event.put("type", "FED_ROUND_COMPLETE");
            event.put("version", version);
            event.put("checksum", checksum);
            event.put("participants", clientModels.size());
            event.put("globalAccuracy", globalAccuracy);
            event.put("evalDatasetId", ev == null ? null : ev.get("dataset_id"));
            event.put("persisted", fedAvgResponse.get("persisted"));
            event.put("message", "Model v" + version + ".0 deployed to " + clientModels.size() + " nodes, "
                + (globalAccuracy != null ? "accuracy " + globalAccuracy + "% on evaluation set " + ev.get("dataset_id")
                                          : "not evaluated (evaluation dataset unavailable)"));
            messagingTemplate.convertAndSend("/topic/events", event);
        } catch (Exception e) {
            System.err.println("Failed FedAvg round: " + e.getMessage());
            // Report the failure; do not present it as a completed round
            Map<String, Object> failEvent = new HashMap<>();
            failEvent.put("type", "FED_ROUND_FAILED");
            failEvent.put("participants", clientModels.size());
            failEvent.put("timestamp", Instant.now().toString());
            failEvent.put("message", "FedAvg aggregation failed (" + clientModels.size() + " local models trained): " + e.getMessage());
            messagingTemplate.convertAndSend("/topic/events", failEvent);
        }
    }
}
