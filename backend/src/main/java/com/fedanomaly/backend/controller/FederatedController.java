package com.fedanomaly.backend.controller;

import org.springframework.web.bind.annotation.*;
import com.fedanomaly.backend.model.FederatedRound;
import com.fedanomaly.backend.repository.FederatedRoundRepository;
import com.fedanomaly.backend.service.FederatedLearningService;
import java.util.List;

@RestController
@RequestMapping("/api/federated")
public class FederatedController {
    private final FederatedLearningService fedLearningService;
    private final FederatedRoundRepository roundRepository;

    public FederatedController(FederatedLearningService fedLearningService, FederatedRoundRepository roundRepository) {
        this.fedLearningService = fedLearningService;
        this.roundRepository = roundRepository;
    }

    @PostMapping("/round")
    public void triggerRound() {
        fedLearningService.runFederatedRound();
    }

    @GetMapping("/rounds")
    public List<FederatedRound> getRounds() {
        return roundRepository.findAll();
    }
}
