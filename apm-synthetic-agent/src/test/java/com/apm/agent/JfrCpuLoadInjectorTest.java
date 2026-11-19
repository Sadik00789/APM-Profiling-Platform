package com.apm.agent;

import com.apm.agent.generator.ChaosStateService;
import com.apm.agent.generator.JfrCpuLoadInjector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JfrCpuLoadInjectorTest {

    @Test
    @DisplayName("Should generate baseline and spike folded profiles with valid frame lines")
    void testFoldedProfileGeneration() {
        ChaosStateService chaos = new ChaosStateService();
        JfrCpuLoadInjector injector = new JfrCpuLoadInjector(chaos);

        String baseline = injector.generateFoldedProfile("order-service", false);
        assertFalse(baseline.contains("BCrypt.hashPassword"));
        assertTrue(baseline.contains("com.apm.order-service.service.processBusinessLogic"));

        // Local spike flag when global chaos is off should still produce spike hotspots with non-zero intensity
        String localSpike = injector.generateFoldedProfile("order-service", true);
        assertTrue(localSpike.contains("BCrypt.hashPassword"));
        assertTrue(localSpike.contains("PatternMatcher.matches"));

        // Global CPU_SPIKE chaos should also emit spike hotspots
        chaos.injectChaos("CPU_SPIKE", 1.0);
        String globalSpike = injector.generateFoldedProfile("payment-service", false);
        assertTrue(globalSpike.contains("com.apm.payment-service.security.AuthValidator.verifyToken"));
        for (String line : globalSpike.split("\n")) {
            assertTrue(line.matches(".+ \\d+$"), "Malformed folded stack line: " + line);
        }
    }
}
