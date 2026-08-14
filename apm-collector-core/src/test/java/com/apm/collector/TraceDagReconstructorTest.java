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
}
