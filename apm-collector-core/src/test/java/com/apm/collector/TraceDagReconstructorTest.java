package com.apm.collector;

import com.apm.collector.engine.tracing.CriticalPathCalculator;
import com.apm.collector.engine.tracing.TraceDagReconstructor;
import com.apm.contracts.trace.v1.SpanRecord;
import com.apm.contracts.trace.v1.StatusCode;
import com.apm.contracts.trace.v1.TraceTreeResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TraceDagReconstructorTest {

    private TraceDagReconstructor reconstructor;

    @BeforeEach
    void setUp() {
        CriticalPathCalculator criticalPathCalculator = new CriticalPathCalculator();
        reconstructor = new TraceDagReconstructor(criticalPathCalculator);
    }

    @Test
    @DisplayName("Should assemble out-of-order spans into a DAG and accurately mark the critical path")
    void testTraceReconstructionAndCriticalPath() {
        String traceId = "trace-test-123";

        // Span 1: Root Gateway (duration 100ms)
        SpanRecord root = SpanRecord.newBuilder()
                .setTraceId(traceId)
                .setSpanId("span-gateway")
                .setParentSpanId("")
                .setServiceName("api-gateway")
                .setOperationName("HTTP GET /orders")
                .setStartTimeUnixNano(1000_000_000L)
                .setEndTimeUnixNano(1100_000_000L)
                .setDurationNano(100_000_000L)
                .setStatusCode(StatusCode.STATUS_CODE_OK)
                .build();

        // Span 2: Payment call (duration 80ms - bottleneck branch)
        SpanRecord payment = SpanRecord.newBuilder()
                .setTraceId(traceId)
                .setSpanId("span-payment")
                .setParentSpanId("span-gateway")
                .setServiceName("payment-service")
                .setOperationName("HTTP POST /charge")
                .setStartTimeUnixNano(1010_000_000L)
                .setEndTimeUnixNano(1090_000_000L)
                .setDurationNano(80_000_000L)
                .setStatusCode(StatusCode.STATUS_CODE_OK)
                .build();

        // Span 3: Fast Inventory call (duration 15ms)
        SpanRecord inventory = SpanRecord.newBuilder()
                .setTraceId(traceId)
                .setSpanId("span-inventory")
                .setParentSpanId("span-gateway")
                .setServiceName("inventory-service")
                .setOperationName("HTTP GET /stock")
                .setStartTimeUnixNano(1010_000_000L)
                .setEndTimeUnixNano(1025_000_000L)
                .setDurationNano(15_000_000L)
                .setStatusCode(StatusCode.STATUS_CODE_OK)
                .build();

        // Pass spans in shuffled / out-of-order sequence
        List<SpanRecord> spans = List.of(inventory, payment, root);

        TraceTreeResponse response = reconstructor.reconstruct(spans);

        assertNotNull(response);
        assertEquals(traceId, response.getTraceId());
        assertEquals(3, response.getTotalSpans());
        assertEquals(2, response.getCriticalPathSpans()); // root + payment
        assertEquals(0, response.getErrorCount());

        assertTrue(response.getRoot().getIsCriticalPath());
        assertEquals("span-gateway", response.getRoot().getSpan().getSpanId());
        assertEquals(2, response.getRoot().getChildrenCount());

        // Verify payment is marked critical path while inventory is not
        var paymentChild = response.getRoot().getChildrenList().stream()
                .filter(c -> "span-payment".equals(c.getSpan().getSpanId()))
                .findFirst().orElseThrow();
        assertTrue(paymentChild.getIsCriticalPath());

        var inventoryChild = response.getRoot().getChildrenList().stream()
                .filter(c -> "span-inventory".equals(c.getSpan().getSpanId()))
                .findFirst().orElseThrow();
        assertFalse(inventoryChild.getIsCriticalPath());
    }

    @Test
    @DisplayName("Should detect and correct NTP distributed clock skew when child starts before parent")
    void testDistributedClockSkewCorrection() {
        String traceId = "trace-skew-456";

        // Parent starts at 1,000,000,000 ns
        SpanRecord parent = SpanRecord.newBuilder()
                .setTraceId(traceId)
                .setSpanId("span-parent")
                .setParentSpanId("")
                .setServiceName("order-service")
                .setOperationName("POST /checkout")
                .setStartTimeUnixNano(1000_000_000L)
                .setEndTimeUnixNano(1050_000_000L)
                .setDurationNano(50_000_000L)
                .setStatusCode(StatusCode.STATUS_CODE_OK)
                .build();

        // Child clock was behind by 5ms (starts at 995,000,000 ns)
        SpanRecord skewedChild = SpanRecord.newBuilder()
                .setTraceId(traceId)
                .setSpanId("span-child-skewed")
                .setParentSpanId("span-parent")
                .setServiceName("payment-service")
                .setOperationName("POST /charge")
                .setStartTimeUnixNano(995_000_000L)
                .setEndTimeUnixNano(1025_000_000L)
                .setDurationNano(30_000_000L)
                .setStatusCode(StatusCode.STATUS_CODE_OK)
                .build();

        TraceTreeResponse response = reconstructor.reconstruct(List.of(parent, skewedChild));

        assertNotNull(response);
        var childNode = response.getRoot().getChildren(0);
        SpanRecord adjustedChild = childNode.getSpan();

        // Adjusted start should be parent start + 1 microsecond (1,000,001,000 ns)
        assertEquals(1000_001_000L, adjustedChild.getStartTimeUnixNano());
        assertEquals("true", adjustedChild.getAttributesOrDefault("meta.clock_skew_adjusted", "false"));
    }

    @Test
    @DisplayName("Should not double-count nested chain durations during critical path calculation")
    void testCriticalPathAvoidsDoubleCountingDeepChains() {
        String traceId = "trace-deep-vs-wide";

        SpanRecord root = span(traceId, "root", "", 0L, 100_000_000L);
        // Shallow-per-step deep chain: 60ms -> 55ms -> 50ms (previously wrongly summed to 165ms)
        SpanRecord branchA = span(traceId, "a1", "root", 5_000_000L, 65_000_000L);
        SpanRecord childA2 = span(traceId, "a2", "a1", 7_000_000L, 62_000_000L);
        SpanRecord leafA3 = span(traceId, "a3", "a2", 9_000_000L, 59_000_000L);
        // Real bottleneck leaf: 90ms
        SpanRecord bottleneckB = span(traceId, "b1", "root", 5_000_000L, 95_000_000L);

        TraceTreeResponse response = reconstructor.reconstruct(List.of(root, branchA, childA2, leafA3, bottleneckB));

        assertEquals(2, response.getCriticalPathSpans());
        var b1Node = response.getRoot().getChildrenList().stream()
                .filter(c -> "b1".equals(c.getSpan().getSpanId()))
                .findFirst().orElseThrow();
        assertTrue(b1Node.getIsCriticalPath());

        var a1Node = response.getRoot().getChildrenList().stream()
                .filter(c -> "a1".equals(c.getSpan().getSpanId()))
                .findFirst().orElseThrow();
        assertFalse(a1Node.getIsCriticalPath());
    }

    @Test
    @DisplayName("Should compute exclusive time using interval union for parallel children and keep orphan spans")
    void testParallelChildrenExclusiveTimeAndOrphans() {
        String traceId = "trace-parallel-orphans";

        // Parent: [0, 100ms], Children: [10ms, 90ms] and [10ms, 25ms] -> union = 80ms -> self = 20ms (20%)
        SpanRecord root = span(traceId, "root", "", 0L, 100_000_000L);
        SpanRecord c1 = span(traceId, "c1", "root", 10_000_000L, 90_000_000L);
        SpanRecord c2 = span(traceId, "c2", "root", 10_000_000L, 25_000_000L);
        // Disconnected orphan span whose parent was dropped
        SpanRecord orphan = span(traceId, "orphan", "missing-parent", 20_000_000L, 30_000_000L);

        TraceTreeResponse response = reconstructor.reconstruct(List.of(c2, orphan, c1, root));

        assertEquals(4, response.getTotalSpans());
        assertEquals(20.0, response.getRoot().getExclusiveTimePercent(), 0.01);
        assertTrue(response.getRoot().getChildrenList().stream().anyMatch(n -> "orphan".equals(n.getSpan().getSpanId())));
    }

    @Test
    @DisplayName("Should handle cyclic parent references without infinite recursion")
    void testCyclicParentReferences() {
        String traceId = "trace-cycle";
        SpanRecord s1 = span(traceId, "s1", "s2", 0L, 50_000_000L);
        SpanRecord s2 = span(traceId, "s2", "s1", 10_000_000L, 40_000_000L);

        TraceTreeResponse response = assertTimeoutPreemptively(java.time.Duration.ofSeconds(2),
                () -> reconstructor.reconstruct(List.of(s1, s2)));
        assertEquals(2, response.getTotalSpans());
    }

    private SpanRecord span(String traceId, String spanId, String parentId, long startNano, long endNano) {
        return SpanRecord.newBuilder()
                .setTraceId(traceId)
                .setSpanId(spanId)
                .setParentSpanId(parentId)
                .setServiceName("svc")
                .setOperationName("op-" + spanId)
                .setStartTimeUnixNano(startNano)
                .setEndTimeUnixNano(endNano)
                .setDurationNano(endNano - startNano)
                .setStatusCode(StatusCode.STATUS_CODE_OK)
                .build();
    }
}
