package com.apm.agent.generator;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/chaos")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class ChaosController {

    private final ChaosStateService chaosStateService;

    @PostMapping("/inject")
    public ResponseEntity<ChaosStateService.ChaosStatus> injectChaos(
            @RequestParam(defaultValue = "CPU_SPIKE") String scenario,
            @RequestParam(defaultValue = "1.0") Double intensity) {

        log.info("Interactive Chaos Injected: scenario={}, intensity={}", scenario, intensity);
        ChaosStateService.ChaosStatus status = chaosStateService.injectChaos(scenario, intensity);
        return ResponseEntity.ok(status);
    }

    @PostMapping("/reset")
    public ResponseEntity<ChaosStateService.ChaosStatus> resetChaos() {
        log.info("Interactive Chaos Reset to baseline normal state");
        ChaosStateService.ChaosStatus status = chaosStateService.resetChaos();
        return ResponseEntity.ok(status);
    }

    @GetMapping("/status")
    public ResponseEntity<ChaosStateService.ChaosStatus> getStatus() {
        return ResponseEntity.ok(chaosStateService.getStatus());
    }
}
