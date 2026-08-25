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
