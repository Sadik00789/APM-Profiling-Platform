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
        ChaosScenario scenario;
        try {
            scenario = ChaosScenario.valueOf(scenarioName.toUpperCase());
        } catch (Exception e) {
            scenario = ChaosScenario.CPU_SPIKE;
        }

        double val = (intensity != null) ? Math.max(0.0, Math.min(1.0, intensity)) : 1.0;
        ChaosStatus newStatus = new ChaosStatus(scenario, val, scenario != ChaosScenario.NONE, System.currentTimeMillis());
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
