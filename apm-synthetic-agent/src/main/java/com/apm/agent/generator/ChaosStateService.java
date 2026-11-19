package com.apm.agent.generator;

import lombok.Getter;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class ChaosStateService {

    public enum ChaosScenario {
        NONE,
        CPU_SPIKE,
        DB_LATENCY,
        ERROR_STORM
    }

    public record ChaosStatus(
            ChaosScenario scenario,
            double intensity,
            boolean active,
            long activatedAtEpochMillis
    ) {}

    private final AtomicReference<ChaosStatus> status = new AtomicReference<>(
            new ChaosStatus(ChaosScenario.NONE, 0.0, false, 0L)
    );

    public ChaosStatus getStatus() {
        return status.get();
    }

    public ChaosStatus injectChaos(String scenarioName, Double intensity) {
        if (scenarioName == null || scenarioName.isBlank()) {
            throw new IllegalArgumentException("Chaos scenario must not be blank");
        }
        ChaosScenario scenario;
        try {
            scenario = ChaosScenario.valueOf(scenarioName.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unknown chaos scenario: " + scenarioName + ". Valid scenarios: NONE, CPU_SPIKE, DB_LATENCY, ERROR_STORM"
            );
        }

        double val = (intensity != null) ? Math.max(0.0, Math.min(1.0, intensity)) : 1.0;
        if (scenario == ChaosScenario.NONE) {
            return resetChaos();
        }
        ChaosStatus newStatus = new ChaosStatus(scenario, val, true, System.currentTimeMillis());
        status.set(newStatus);
        return newStatus;
    }

    public ChaosStatus resetChaos() {
        ChaosStatus newStatus = new ChaosStatus(ChaosScenario.NONE, 0.0, false, 0L);
        status.set(newStatus);
        return newStatus;
    }

    public boolean isScenarioActive(ChaosScenario scenario) {
        ChaosStatus current = status.get();
        return current.active() && current.scenario() == scenario;
    }
}
