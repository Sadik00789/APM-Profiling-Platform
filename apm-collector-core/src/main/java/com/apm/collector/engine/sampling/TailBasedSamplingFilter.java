package com.apm.collector.engine.sampling;

import com.apm.collector.storage.ClickHouseBatchFlusher;
import com.apm.contracts.trace.v1.SpanRecord;
import com.apm.contracts.trace.v1.StatusCode;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Component
public class TailBasedSamplingFilter {

    private final ClickHouseBatchFlusher batchFlusher;
    private final double p95ThresholdNano;
    private final double baselineSampleRate; // 0.02 = 2%
    private final long traceWindowMillis;

    private static final long DEFAULT_TRACE_WINDOW_MILLIS = 5_000L; // 5-second evaluation window

    private static class TraceBuffer {
        final String traceId;
        final long createdAt;
        final List<SpanRecord> spans = new ArrayList<>();
        boolean forceSample = false;
        boolean flushed = false;
        long totalDurationNano = 0;
        final ReentrantLock lock = new ReentrantLock();

        TraceBuffer(String traceId) {
            this.traceId = traceId;
            this.createdAt = System.currentTimeMillis();
        }
    }

    private final Map<String, TraceBuffer> activeTraces = new ConcurrentHashMap<>();

    @Autowired
    public TailBasedSamplingFilter(
            ClickHouseBatchFlusher batchFlusher,
            @Value("${apm.anomaly.p95-alert-threshold-ms:250.0}") double p95ThresholdMs,
            @Value("${apm.sampling.baseline-rate:0.02}") double baselineSampleRate) {
        this(batchFlusher, p95ThresholdMs, baselineSampleRate, DEFAULT_TRACE_WINDOW_MILLIS);
    }

    public TailBasedSamplingFilter(
            ClickHouseBatchFlusher batchFlusher,
            double p95ThresholdMs,
            double baselineSampleRate,
            long traceWindowMillis) {
        this.batchFlusher = batchFlusher;
        this.p95ThresholdNano = p95ThresholdMs * 1_000_000L;
        this.baselineSampleRate = baselineSampleRate;
        this.traceWindowMillis = traceWindowMillis;
    }

    public void acceptSpan(SpanRecord span) {
        if (span == null || span.getTraceId() == null || span.getTraceId().isBlank()) return;
        String traceId = span.getTraceId();

        while (true) {
            TraceBuffer buffer = activeTraces.computeIfAbsent(traceId, TraceBuffer::new);
            buffer.lock.lock();
            try {
                if (buffer.flushed) {
                    continue;
                }
                buffer.spans.add(span);
                buffer.totalDurationNano = Math.max(buffer.totalDurationNano, span.getDurationNano());

                // 100% Sampling trigger 1: Error status or 4xx/5xx HTTP status
                if (span.getStatusCode() == StatusCode.STATUS_CODE_ERROR) {
                    buffer.forceSample = true;
                }
                String httpStatus = span.getAttributesOrDefault("http.status_code", "");
                if (httpStatus.startsWith("5") || httpStatus.startsWith("4")) {
                    buffer.forceSample = true;
                }

                // 100% Sampling trigger 2: Debug / high priority tag
                if ("true".equalsIgnoreCase(span.getAttributesOrDefault("debug", "")) ||
                    "high".equalsIgnoreCase(span.getAttributesOrDefault("priority", ""))) {
                    buffer.forceSample = true;
                }

                // 100% Sampling trigger 3: Latency exceeds P95 SLA threshold
                if (span.getDurationNano() >= p95ThresholdNano) {
                    buffer.forceSample = true;
                }
                return;
            } finally {
                buffer.lock.unlock();
            }
        }
    }

    @Scheduled(fixedDelay = 1000)
    public void evaluateAndFlushTraces() {
        long now = System.currentTimeMillis();
        List<String> expiredTraceIds = new ArrayList<>();

        for (Map.Entry<String, TraceBuffer> entry : activeTraces.entrySet()) {
            TraceBuffer buffer = entry.getValue();
            if (now - buffer.createdAt >= traceWindowMillis) {
                expiredTraceIds.add(entry.getKey());
            }
        }

        for (String traceId : expiredTraceIds) {
            TraceBuffer buffer = activeTraces.remove(traceId);
            if (buffer != null) {
                evaluateTraceBuffer(buffer);
            }
        }
    }

    private void evaluateTraceBuffer(TraceBuffer buffer) {
        buffer.lock.lock();
        try {
            if (buffer.flushed) {
                return;
            }
            buffer.flushed = true;
            boolean shouldSample = buffer.forceSample ||
                                   (buffer.totalDurationNano >= p95ThresholdNano) ||
                                   (ThreadLocalRandom.current().nextDouble() < baselineSampleRate);

            if (shouldSample) {
                for (SpanRecord span : buffer.spans) {
                    batchFlusher.enqueueSpan(span);
                }
            } else {
                log.debug("Tail-sampling discarded healthy trace {}", buffer.traceId);
            }
        } finally {
            buffer.lock.unlock();
        }
    }

    @PreDestroy
    public void flushAllRemaining() {
        for (String traceId : new ArrayList<>(activeTraces.keySet())) {
            TraceBuffer buffer = activeTraces.remove(traceId);
            if (buffer != null) {
                evaluateTraceBuffer(buffer);
            }
        }
    }
}
