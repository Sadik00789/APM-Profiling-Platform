package com.apm.agent.generator;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class TraceContextPropagator {

    private static final Pattern TRACEPARENT_PATTERN =
            Pattern.compile("^00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$");
    private static final String ZERO_TRACE_ID = "00000000000000000000000000000000";
    private static final String ZERO_SPAN_ID = "0000000000000000";

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
        if (traceparent == null) {
            return createRootContext();
        }
        Matcher matcher = TRACEPARENT_PATTERN.matcher(traceparent.trim());
        if (!matcher.matches()) {
            return createRootContext();
        }
        String traceId = matcher.group(1);
        String parentSpanId = matcher.group(2);
        if (ZERO_TRACE_ID.equals(traceId) || ZERO_SPAN_ID.equals(parentSpanId)) {
            return createRootContext();
        }
        String newSpanId = generateSpanId();
        String newTraceparent = "00-" + traceId + "-" + newSpanId + "-01";
        return new TraceContext(traceId, newSpanId, parentSpanId, newTraceparent);
    }
}
