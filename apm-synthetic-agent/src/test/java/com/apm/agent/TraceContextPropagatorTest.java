package com.apm.agent;

import com.apm.agent.generator.TraceContextPropagator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TraceContextPropagatorTest {

    private final TraceContextPropagator propagator = new TraceContextPropagator();

    @Test
    @DisplayName("Should create valid W3C root and child contexts sharing traceId")
    void testRootAndChildContexts() {
        var root = propagator.createRootContext();
        assertEquals(32, root.traceId().length());
        assertEquals(16, root.spanId().length());
        assertEquals("", root.parentSpanId());
        assertTrue(root.traceparent().matches("^00-[0-9a-f]{32}-[0-9a-f]{16}-01$"));

        var child = propagator.createChildContext(root);
        assertEquals(root.traceId(), child.traceId());
        assertEquals(root.spanId(), child.parentSpanId());
        assertNotEquals(root.spanId(), child.spanId());
    }

    @Test
    @DisplayName("Should strictly validate W3C traceparent headers and fall back on invalid inputs")
    void testStrictTraceParentParsing() {
        String validTraceId = "0af7651916cd43dd8448eb211c80319c";
        String validParentSpanId = "b7ad6b7169203331";
        var parsed = propagator.parseTraceParent("00-" + validTraceId + "-" + validParentSpanId + "-01");

        assertEquals(validTraceId, parsed.traceId());
        assertEquals(validParentSpanId, parsed.parentSpanId());
        assertNotEquals(validParentSpanId, parsed.spanId());

        // Malformed, uppercase, wrong version, or all-zero IDs must yield a fresh root context
        String[] invalidHeaders = {
                null,
                "",
                "01-" + validTraceId + "-" + validParentSpanId + "-01",
                "00-nothex-" + validParentSpanId + "-01",
                "00-00000000000000000000000000000000-" + validParentSpanId + "-01",
                "00-" + validTraceId + "-0000000000000000-01",
                "00-" + validTraceId.toUpperCase() + "-" + validParentSpanId + "-01"
        };

        for (String invalid : invalidHeaders) {
            var fallback = propagator.parseTraceParent(invalid);
            assertEquals("", fallback.parentSpanId());
            assertNotEquals(validTraceId, fallback.traceId());
        }
    }
}
