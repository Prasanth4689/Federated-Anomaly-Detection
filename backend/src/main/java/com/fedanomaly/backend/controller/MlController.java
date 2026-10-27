package com.fedanomaly.backend.controller;

import com.fedanomaly.backend.service.MlWarmupService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/ml")
public class MlController {
    private final MlWarmupService warmupService;

    public MlController(MlWarmupService warmupService) {
        this.warmupService = warmupService;
    }

    /** Starts waking the ML service in the background and returns immediately. */
    @PostMapping("/wake")
    public Map<String, Object> wake() {
        warmupService.wake();
        return warmupService.status();
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return warmupService.status();
    }
}
