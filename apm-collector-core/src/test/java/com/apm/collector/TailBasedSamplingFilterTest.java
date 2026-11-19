package com.apm.collector;

import com.apm.collector.engine.sampling.TailBasedSamplingFilter;
import com.apm.collector.storage.ClickHouseBatchFlusher;
import com.apm.contracts.trace.v1.SpanRecord;
import com.apm.contracts.trace.v1.StatusCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TailBasedSamplingFilterTest {

    @Test
    @DisplayName("Should 100% retain error, 4xx/5xx, debug/high-priority, and slow spans even at 0% baseline rate")
    void testForceSamplingTriggers() {
        ClickHouseBatchFlusher flusher = mock(ClickHouseBatchFlusher.class);
        TailBasedSamplingFilter filter = new TailBasedSamplingFilter(flusher, 250.0, 0.0, 5_000L);

        filter.acceptSpan(span("t-err", "s1", 10_000_000L, StatusCode.STATUS_CODE_ERROR, null, null));
        filter.acceptSpan(span("t-500", "s2", 10_000_000L, StatusCode.STATUS_CODE_OK, "http.status_code", "503"));
        filter.acceptSpan(span("t-404", "s3", 10_000_000L, StatusCode.STATUS_CODE_OK, "http.status_code", "404"));
        filter.acceptSpan(span("t-dbg", "s4", 10_000_000L, StatusCode.STATUS_CODE_OK, "debug", "true"));
        filter.acceptSpan(span("t-pri", "s5", 10_000_000L, StatusCode.STATUS_CODE_OK, "priority", "high"));
        filter.acceptSpan(span("t-slow", "s6", 300_000_000L, StatusCode.STATUS_CODE_OK, null, null));
        filter.acceptSpan(span("t-fast", "s7", 15_000_000L, StatusCode.STATUS_CODE_OK, "http.status_code", "200"));

        filter.flushAllRemaining();

        verify(flusher, times(6)).enqueueSpan(any());
        verify(flusher, never()).enqueueSpan(argThat(s -> "t-fast".equals(s.getTraceId())));
    }

    @Test
    @DisplayName("Should flush expired traces after window and prevent double flush on repeated shutdown")
    void testWindowExpirationAndNoDoubleFlush() throws InterruptedException {
        ClickHouseBatchFlusher flusher = mock(ClickHouseBatchFlusher.class);
        TailBasedSamplingFilter filter = new TailBasedSamplingFilter(flusher, 250.0, 1.0, 30L);

        filter.acceptSpan(span("t-1", "s1", 10_000_000L, StatusCode.STATUS_CODE_OK, null, null));
        Thread.sleep(60L);
        filter.evaluateAndFlushTraces();

        verify(flusher, times(1)).enqueueSpan(any());

        // Calling again and flushing remaining must not duplicate
        filter.evaluateAndFlushTraces();
        filter.flushAllRemaining();
        filter.flushAllRemaining();
        verify(flusher, times(1)).enqueueSpan(any());
    }

    @Test
    @DisplayName("Should not drop spans when acceptSpan races with evaluateAndFlushTraces")
    void testConcurrentAcceptAndFlushDropsZeroSpans() throws Exception {
        ClickHouseBatchFlusher flusher = mock(ClickHouseBatchFlusher.class);
        AtomicInteger flushedCount = new AtomicInteger();
        doAnswer(inv -> {
            flushedCount.incrementAndGet();
            return null;
        }).when(flusher).enqueueSpan(any());

        TailBasedSamplingFilter filter = new TailBasedSamplingFilter(flusher, 250.0, 1.0, 5L);
        int writers = 4;
        int spansPerWriter = 250;
        ExecutorService pool = Executors.newFixedThreadPool(writers + 1);
        CountDownLatch start = new CountDownLatch(1);
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();

        for (int w = 0; w < writers; w++) {
            int workerId = w;
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < spansPerWriter; i++) {
                    String traceId = "trace-" + (i % 20);
                    filter.acceptSpan(span(traceId, "span-" + workerId + "-" + i, 5_000_000L, StatusCode.STATUS_CODE_OK, null, null));
                    if (i % 25 == 0) Thread.sleep(1);
                }
                return null;
            }));
        }

        futures.add(pool.submit(() -> {
            start.await();
            for (int i = 0; i < 40; i++) {
                filter.evaluateAndFlushTraces();
                Thread.sleep(2);
            }
            return null;
        }));

        start.countDown();
        for (var f : futures) f.get();
        filter.flushAllRemaining();
        pool.shutdown();

        assertEquals(writers * spansPerWriter, flushedCount.get());
    }

    private SpanRecord span(String traceId, String spanId, long durationNano, StatusCode status, String attrKey, String attrVal) {
        SpanRecord.Builder b = SpanRecord.newBuilder()
                .setTraceId(traceId)
                .setSpanId(spanId)
                .setServiceName("order-service")
                .setOperationName("GET /orders")
                .setDurationNano(durationNano)
                .setStatusCode(status);
        if (attrKey != null) {
            b.putAttributes(attrKey, attrVal);
        }
        return b.build();
    }
}
