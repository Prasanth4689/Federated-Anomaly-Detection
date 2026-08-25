package com.fedanomaly.backend.controller;
import org.springframework.web.bind.annotation.*;
import com.fedanomaly.backend.service.AttackService;
import java.util.Map;

@RestController
@RequestMapping("/api/attacks")
public class AttackController {
    private final AttackService attackService;
    public AttackController(AttackService attackService) { this.attackService = attackService; }
    
    @PostMapping("/inject")
    public void injectAttack(@RequestParam(required = false) String target, @RequestParam(required = false) String type) {
        if (target != null && type != null) {
            attackService.injectAttack(target, type);
        } else {
            attackService.injectAttack();
        }
    }
    
    @PostMapping("/stop")
    public void stopAttack(@RequestParam String target) {
        attackService.stopAttack(target);
    }
    
    @PostMapping("/stopAll")
    public void stopAll() {
        attackService.stopAll();
    }
    
    @GetMapping("/active")
    public Map<String, String> getActiveAttacks() {
        return attackService.getActiveAttacks();
    }
}
