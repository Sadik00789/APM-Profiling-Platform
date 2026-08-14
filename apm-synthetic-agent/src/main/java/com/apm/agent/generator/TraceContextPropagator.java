package com.apm.agent.generator;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.HexFormat;

@Component
public class TraceContextPropagator {

    private final SecureRandom random = new SecureRandom();

    public record TraceContext(String traceId, String spanId, String parentSpanId, String traceparent) {}

    public String generateTraceId() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    public String generateSpanId() {
        byte[] bytes = new byte[8];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    public TraceContext createRootContext() {
        String traceId = generateTraceId();
        String spanId = generateSpanId();
        String traceparent = "00-" + traceId + "-" + spanId + "-01";
        return new TraceContext(traceId, spanId, "", traceparent);
    }

    public TraceContext createChildContext(TraceContext parent) {
        String childSpanId = generateSpanId();
        String traceparent = "00-" + parent.traceId() + "-" + childSpanId + "-01";
        return new TraceContext(parent.traceId(), childSpanId, parent.spanId(), traceparent);
    }

    public TraceContext parseTraceParent(String traceparent) {
        if (traceparent == null || !traceparent.startsWith("00-")) {
            return createRootContext();
        }
        String[] parts = traceparent.split("-");
        if (parts.length < 4) {
            return createRootContext();
        }
        String traceId = parts[1];
        String parentSpanId = parts[2];
        String newSpanId = generateSpanId();
        String newTraceparent = "00-" + traceId + "-" + newSpanId + "-01";
        return new TraceContext(traceId, newSpanId, parentSpanId, newTraceparent);
    }
}
