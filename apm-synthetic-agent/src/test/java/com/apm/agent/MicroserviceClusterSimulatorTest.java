package com.apm.agent;

import com.apm.agent.emitter.OtlpSpanEmitter;
import com.apm.agent.generator.ChaosStateService;
import com.apm.agent.generator.MicroserviceClusterSimulator;
import com.apm.agent.generator.TraceContextPropagator;
import com.apm.contracts.trace.v1.SpanRecord;
import com.apm.contracts.trace.v1.StatusCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class MicroserviceClusterSimulatorTest {

    @Test
    @DisplayName("Should guarantee every parent span strictly encloses all child spans across all chaos modes")
    void testParentEnclosesChildrenAcrossAllScenarios() {
        ChaosStateService chaos = new ChaosStateService();
        MicroserviceClusterSimulator simulator = new MicroserviceClusterSimulator(
                new TraceContextPropagator(),
                mock(OtlpSpanEmitter.class),
                chaos
        );

        String[] scenarios = {"NONE", "CPU_SPIKE", "DB_LATENCY", "ERROR_STORM"};
        for (String scenario : scenarios) {
            chaos.injectChaos(scenario, 1.0);
            List<SpanRecord> spans = simulator.generateTraceSpans();
            assertEquals(7, spans.size(), "Expected 7 spans for scenario " + scenario);

            String traceId = spans.get(0).getTraceId();
            Map<String, SpanRecord> byId = spans.stream()
                    .collect(Collectors.toMap(SpanRecord::getSpanId, Function.identity()));

            for (SpanRecord child : spans) {
                assertEquals(traceId, child.getTraceId());
                assertTrue(child.getDurationNano() > 0);
                assertEquals(child.getStartTimeUnixNano() + child.getDurationNano(), child.getEndTimeUnixNano());

                if (!child.getParentSpanId().isEmpty()) {
                    SpanRecord parent = byId.get(child.getParentSpanId());
                    assertNotNull(parent, "Missing parent span for " + child.getOperationName());
                    assertTrue(
                            child.getStartTimeUnixNano() >= parent.getStartTimeUnixNano(),
                            scenario + ": child " + child.getOperationName() + " started before parent " + parent.getOperationName()
                    );
                    assertTrue(
                            child.getEndTimeUnixNano() <= parent.getEndTimeUnixNano(),
                            scenario + ": child " + child.getOperationName() + " ended after parent " + parent.getOperationName()
                    );
                }
            }
        }
    }

    @Test
    @DisplayName("Should inject expected DB latency, CPU spike latency, and high error storm rate")
    void testChaosScenarioEffects() {
        ChaosStateService chaos = new ChaosStateService();
        MicroserviceClusterSimulator simulator = new MicroserviceClusterSimulator(
                new TraceContextPropagator(),
                mock(OtlpSpanEmitter.class),
                chaos
        );

        chaos.injectChaos("DB_LATENCY", 1.0);
        List<SpanRecord> dbSpans = simulator.generateTraceSpans();
        SpanRecord dbSpan = dbSpans.stream()
                .filter(s -> s.getOperationName().startsWith("SQL UPDATE"))
                .findFirst().orElseThrow();
        assertTrue(dbSpan.getDurationNano() >= 350_000_000L);
        assertEquals("true", dbSpan.getAttributesOrDefault("db.latency_injected", "false"));

        chaos.injectChaos("CPU_SPIKE", 1.0);
        List<SpanRecord> cpuSpans = simulator.generateTraceSpans();
        SpanRecord paySpan = cpuSpans.stream()
                .filter(s -> "payment-service".equals(s.getServiceName()) && "POST /payments/charge".equals(s.getOperationName()))
                .findFirst().orElseThrow();
        assertTrue(paySpan.getDurationNano() >= 320_000_000L);

        chaos.injectChaos("ERROR_STORM", 1.0);
        int errorTraces = 0;
        int runs = 40;
        for (int i = 0; i < runs; i++) {
            boolean hasError = simulator.generateTraceSpans().stream()
                    .anyMatch(s -> s.getStatusCode() == StatusCode.STATUS_CODE_ERROR);
            if (hasError) errorTraces++;
        }
        assertTrue(errorTraces >= runs / 2, "Expected high error rate during ERROR_STORM, got " + errorTraces + "/" + runs);
    }
}
