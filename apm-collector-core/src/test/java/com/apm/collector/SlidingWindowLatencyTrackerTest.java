package com.apm.collector;

import com.apm.collector.config.RedisStreamConfig;
import com.apm.collector.engine.anomaly.SlidingWindowLatencyTracker;
import com.apm.contracts.query.v1.PercentileMetric;
import com.apm.contracts.query.v1.ServiceMetricsSummary;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SlidingWindowLatencyTrackerTest {

    private ReactiveRedisTemplate<String, String> redisTemplate;
    private SlidingWindowLatencyTracker tracker;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redisTemplate = mock(ReactiveRedisTemplate.class);
        when(redisTemplate.convertAndSend(anyString(), anyString())).thenReturn(Mono.just(1L));
        tracker = new SlidingWindowLatencyTracker(250.0, 500.0, redisTemplate, new ObjectMapper());
    }

    @Test
    @DisplayName("Should pool raw samples across operations and track accurate error rates")
    void testPooledPercentilesAndErrors() {
        // 1 sample on opA (10ms, error) and 9 samples on opB (20..100ms, 1 error) -> total 10 samples, 2 errors = 20%
        tracker.recordSpan("payment-service", "opA", 10.0, true);
        for (int i = 2; i <= 10; i++) {
            tracker.recordSpan("payment-service", "opB", i * 10.0, i == 10);
        }

        PercentileMetric pooled = tracker.getServicePercentiles("payment-service");
        assertEquals(10L, pooled.getTotalRequests());
        assertEquals(2L, pooled.getErrorCount());
        assertEquals(60.0, pooled.getP50Ms());
        assertEquals(100.0, pooled.getMaxMs());

        List<ServiceMetricsSummary> summaries = tracker.getAllServiceSummaries();
        assertEquals(1, summaries.size());
        ServiceMetricsSummary summary = summaries.get(0);
        assertEquals("payment-service", summary.getServiceName());
        assertEquals(20.0, summary.getErrorRatePercent());
        assertEquals(60.0, summary.getMetrics().getP50Ms());
    }

    @Test
    @DisplayName("Should compute 7-bucket latency histogram accurately")
    void testLatencyHistogramBuckets() {
        tracker.recordSpan("order-service", "checkout", 5.0, false);   // 0-10ms
        tracker.recordSpan("order-service", "checkout", 15.0, false);  // 10-25ms
        tracker.recordSpan("order-service", "checkout", 35.0, false);  // 25-50ms
        tracker.recordSpan("order-service", "checkout", 75.0, false);  // 50-100ms
        tracker.recordSpan("order-service", "checkout", 150.0, false); // 100-250ms
        tracker.recordSpan("order-service", "checkout", 300.0, false); // 250-500ms
        tracker.recordSpan("order-service", "checkout", 600.0, true);  // 500ms+

        Optional<Map<String, Object>> opt = tracker.getServiceLatencyHistogram("order-service");
        assertTrue(opt.isPresent());
        Map<String, Object> hist = opt.get();
        assertEquals(7L, hist.get("totalCount"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> buckets = (List<Map<String, Object>>) hist.get("buckets");
        assertEquals(7, buckets.size());
        for (Map<String, Object> b : buckets) {
            assertEquals(1L, b.get("count"));
        }
    }

    @Test
    @DisplayName("Should publish alerts for both P95 and P99 threshold breaches safely even with null operation")
    void testP95AndP99AnomalyAlerts() {
        tracker.recordSpan("api-gateway", null, 275.0, false);
        tracker.recordSpan("api-gateway", "POST /checkout", 550.0, true);

        verify(redisTemplate, times(1)).convertAndSend(
                eq(RedisStreamConfig.ANOMALY_ALERT_CHANNEL),
                argThat((String json) -> json.contains("P95_THRESHOLD_EXCEEDED") && json.contains("\"operationName\":\"default\""))
        );
        verify(redisTemplate, times(1)).convertAndSend(
                eq(RedisStreamConfig.ANOMALY_ALERT_CHANNEL),
                argThat((String json) -> json.contains("P99_THRESHOLD_EXCEEDED"))
        );
    }
}
