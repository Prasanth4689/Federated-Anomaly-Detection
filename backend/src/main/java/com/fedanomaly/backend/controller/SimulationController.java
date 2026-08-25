package com.fedanomaly.backend.controller;

import org.springframework.web.bind.annotation.*;
import com.fedanomaly.backend.service.SimulationService;
import java.util.Map;

@RestController
@RequestMapping("/api/simulation")
public class SimulationController {
    private final SimulationService simulationService;

    public SimulationController(SimulationService simulationService) {
        this.simulationService = simulationService;
    }

    @PostMapping("/start")
    public Map<String, Object> start() { 
        simulationService.startSimulation(); 
        return simulationService.getSimulationState();
    }

    @PostMapping("/pause")
    public Map<String, Object> pause() { 
        simulationService.pauseSimulation(); 
        return simulationService.getSimulationState();
    }

    @PostMapping("/stop")
    public Map<String, Object> stop() { 
        simulationService.stopSimulation(); 
        return simulationService.getSimulationState();
    }

    @PostMapping("/speed")
    public Map<String, Object> setSpeed(@RequestParam int multiplier) {
        simulationService.setSpeed(multiplier);
        return simulationService.getSimulationState();
    }
    
    @GetMapping("/state")
    public Map<String, Object> getState() { 
        return simulationService.getSimulationState(); 
    }
}
