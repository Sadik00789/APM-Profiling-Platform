package com.apm.agent;

import com.apm.agent.generator.ChaosController;
import com.apm.agent.generator.ChaosStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.*;

class ChaosStateServiceTest {

    private ChaosStateService service;
    private ChaosController controller;

    @BeforeEach
    void setUp() {
        service = new ChaosStateService();
        controller = new ChaosController(service);
    }

    @Test
    @DisplayName("Should activate valid chaos scenarios, clamp intensity, and reset cleanly")
    void testValidScenariosAndClamp() {
        var initial = service.getStatus();
        assertFalse(initial.active());
        assertEquals(ChaosStateService.ChaosScenario.NONE, initial.scenario());

        var cpu = service.injectChaos("cpu_spike", 1.8);
        assertTrue(cpu.active());
        assertEquals(ChaosStateService.ChaosScenario.CPU_SPIKE, cpu.scenario());
        assertEquals(1.0, cpu.intensity());
        assertTrue(service.isScenarioActive(ChaosStateService.ChaosScenario.CPU_SPIKE));

        var db = service.injectChaos("DB_LATENCY", -0.5);
        assertTrue(db.active());
        assertEquals(ChaosStateService.ChaosScenario.DB_LATENCY, db.scenario());
        assertEquals(0.0, db.intensity());

        var reset = service.injectChaos("NONE", 1.0);
        assertFalse(reset.active());
        assertEquals(ChaosStateService.ChaosScenario.NONE, reset.scenario());
        assertEquals(0.0, reset.intensity());
    }

    @Test
    @DisplayName("Should reject blank or unknown scenarios instead of silently triggering CPU_SPIKE")
    void testRejectsInvalidScenarios() {
        assertThrows(IllegalArgumentException.class, () -> service.injectChaos("UNKNOWN_SCENARIO", 1.0));
        assertThrows(IllegalArgumentException.class, () -> service.injectChaos("   ", 1.0));
        assertThrows(IllegalArgumentException.class, () -> service.injectChaos(null, 1.0));

        assertFalse(service.getStatus().active());

        var errResp = controller.handleInvalidScenario(new IllegalArgumentException("bad"));
        assertEquals(HttpStatus.BAD_REQUEST, errResp.getStatusCode());
        assertEquals("bad", errResp.getBody().get("error"));
    }
}
