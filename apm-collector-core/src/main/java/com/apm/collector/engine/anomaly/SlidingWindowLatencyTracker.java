package com.apm.collector.engine.anomaly;

import com.apm.collector.config.RedisStreamConfig;
import com.apm.contracts.query.v1.PercentileMetric;
import com.apm.contracts.query.v1.ServiceMetricsSummary;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Slf4j
@Component
public class SlidingWindowLatencyTracker {

    private static final int WINDOW_SIZE_SECONDS = 60;

    private final double p95AlertThresholdMs;
    private final double p99AlertThresholdMs;
    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    // key: "service:operation"
    private final Map<String, ServiceRollingBuffer> serviceBuffers = new ConcurrentHashMap<>();

    public SlidingWindowLatencyTracker(
            @Value("${apm.anomaly.p95-alert-threshold-ms:250.0}") double p95AlertThresholdMs,
            @Value("${apm.anomaly.p99-alert-threshold-ms:500.0}") double p99AlertThresholdMs,
            ReactiveRedisTemplate<String, String> redisTemplate,
            ObjectMapper objectMapper) {
        this.p95AlertThresholdMs = p95AlertThresholdMs;
        this.p99AlertThresholdMs = p99AlertThresholdMs;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public void recordLatency(String serviceName, String operationName, double latencyMs) {
        recordSpan(serviceName, operationName, latencyMs, false);
    }

    public void recordSpan(String serviceName, String operationName, double latencyMs, boolean isError) {
        if (serviceName == null || serviceName.isBlank()) return;
        String op = (operationName != null && !operationName.isBlank()) ? operationName : "default";
        String key = serviceName + ":" + op;

        ServiceRollingBuffer buffer = serviceBuffers.computeIfAbsent(key, k -> new ServiceRollingBuffer(serviceName, op));
        buffer.record(latencyMs, isError);

        // Check for latency anomaly
        if (latencyMs >= p99AlertThresholdMs) {
            checkAndEmitAnomaly(serviceName, op, latencyMs, "P99_THRESHOLD_EXCEEDED");
        } else if (latencyMs >= p95AlertThresholdMs) {
            checkAndEmitAnomaly(serviceName, op, latencyMs, "P95_THRESHOLD_EXCEEDED");
        }
    }

    public PercentileMetric getPercentiles(String serviceName, String operationName) {
        if (serviceName == null || serviceName.isBlank()) {
            return PercentileMetric.getDefaultInstance();
        }
        String op = (operationName != null && !operationName.isBlank()) ? operationName : "default";
        String key = serviceName + ":" + op;
        ServiceRollingBuffer buffer = serviceBuffers.get(key);
        if (buffer == null) {
            return PercentileMetric.getDefaultInstance();
        }
        return buffer.calculatePercentiles();
    }

    public PercentileMetric getServicePercentiles(String serviceName) {
        if (serviceName == null || serviceName.isBlank()) {
            return PercentileMetric.getDefaultInstance();
        }
        long currentSec = System.currentTimeMillis() / 1000L;
        DoubleArrayList pooled = new DoubleArrayList(256);
        long totalErrors = 0;

        for (ServiceRollingBuffer buf : serviceBuffers.values()) {
            if (serviceName.equals(buf.serviceName)) {
                totalErrors += buf.collectSamplesInto(pooled, currentSec);
            }
        }
        return buildPercentileMetric(pooled, totalErrors);
    }

    public List<ServiceMetricsSummary> getAllServiceSummaries() {
        Map<String, List<ServiceRollingBuffer>> byService = new HashMap<>();
        for (ServiceRollingBuffer buf : serviceBuffers.values()) {
            byService.computeIfAbsent(buf.serviceName, k -> new ArrayList<>()).add(buf);
        }

        long currentSec = System.currentTimeMillis() / 1000L;
        List<ServiceMetricsSummary> summaries = new ArrayList<>();
        for (Map.Entry<String, List<ServiceRollingBuffer>> entry : byService.entrySet()) {
            String serviceName = entry.getKey();
            List<ServiceRollingBuffer> buffers = entry.getValue();

            DoubleArrayList pooled = new DoubleArrayList(256);
            long totalErrors = 0;
            for (ServiceRollingBuffer buf : buffers) {
                totalErrors += buf.collectSamplesInto(pooled, currentSec);
            }

            PercentileMetric aggregateMetric = buildPercentileMetric(pooled, totalErrors);
            long totalReqs = aggregateMetric.getTotalRequests();
            if (totalReqs == 0) {
                continue;
            }

            double rps = (double) totalReqs / WINDOW_SIZE_SECONDS;
            double errorRatePercent = ((double) totalErrors / totalReqs) * 100.0;

            summaries.add(ServiceMetricsSummary.newBuilder()
                    .setServiceName(serviceName)
                    .setMetrics(aggregateMetric)
                    .setRps(Math.round(rps * 10.0) / 10.0)
                    .setErrorRatePercent(Math.round(errorRatePercent * 100.0) / 100.0)
                    .setActiveInstances(1)
                    .build());
        }

        return summaries;
    }

    public Optional<Map<String, Object>> getServiceLatencyHistogram(String serviceName) {
        if (serviceName == null || serviceName.isBlank()) {
            return Optional.empty();
        }
        long currentSec = System.currentTimeMillis() / 1000L;
        DoubleArrayList pooled = new DoubleArrayList(512);
        long totalErrors = 0;

        for (ServiceRollingBuffer buf : serviceBuffers.values()) {
            if (serviceName.equals(buf.serviceName)) {
                totalErrors += buf.collectSamplesInto(pooled, currentSec);
            }
        }

        if (pooled.isEmpty()) {
            return Optional.empty();
        }

        long[] counts = new long[7];
        for (int i = 0; i < pooled.size(); i++) {
            double v = pooled.getDouble(i);
            if (v < 10.0) counts[0]++;
            else if (v < 25.0) counts[1]++;
            else if (v < 50.0) counts[2]++;
            else if (v < 100.0) counts[3]++;
            else if (v < 250.0) counts[4]++;
            else if (v < 500.0) counts[5]++;
            else counts[6]++;
        }

        List<Map<String, Object>> buckets = List.of(
                Map.of("range", "0-10ms", "count", counts[0], "min", 0, "max", 10),
                Map.of("range", "10-25ms", "count", counts[1], "min", 10, "max", 25),
                Map.of("range", "25-50ms", "count", counts[2], "min", 25, "max", 50),
                Map.of("range", "50-100ms", "count", counts[3], "min", 50, "max", 100),
                Map.of("range", "100-250ms", "count", counts[4], "min", 100, "max", 250),
                Map.of("range", "250-500ms", "count", counts[5], "min", 250, "max", 500),
                Map.of("range", "500ms+", "count", counts[6], "min", 500, "max", 2000)
        );

        PercentileMetric m = buildPercentileMetric(pooled, totalErrors);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("serviceName", serviceName);
        result.put("p50Ms", m.getP50Ms());
        result.put("p95Ms", m.getP95Ms());
        result.put("p99Ms", m.getP99Ms());
        result.put("buckets", buckets);
        result.put("totalCount", m.getTotalRequests());
        return Optional.of(result);
    }

    private void checkAndEmitAnomaly(String serviceName, String operationName, double latencyMs, String reason) {
        if (redisTemplate == null) return;
        try {
            Map<String, Object> alert = Map.of(
                    "type", "LATENCY_ANOMALY",
                    "serviceName", serviceName,
                    "operationName", operationName != null ? operationName : "default",
                    "observedLatencyMs", latencyMs,
                    "reason", reason,
                    "timestamp", System.currentTimeMillis()
            );
            String json = objectMapper.writeValueAsString(alert);
            redisTemplate.convertAndSend(RedisStreamConfig.ANOMALY_ALERT_CHANNEL, json)
                    .subscribe(c -> {}, err -> log.debug("Failed to send anomaly alert to Redis: {}", err.getMessage()));
        } catch (Exception e) {
            log.debug("Failed to send anomaly alert to Redis: {}", e.getMessage());
        }
    }

    private static PercentileMetric buildPercentileMetric(DoubleArrayList samples, long errorCount) {
        if (samples.isEmpty()) {
            return PercentileMetric.getDefaultInstance();
        }

        double[] arr = samples.toDoubleArray();
        Arrays.sort(arr);

        int n = arr.length;
        double p50 = arr[(int) (n * 0.50)];
        double p95 = arr[(int) Math.min(n - 1, n * 0.95)];
        double p99 = arr[(int) Math.min(n - 1, n * 0.99)];
        double max = arr[n - 1];

        double sum = 0;
        for (double v : arr) sum += v;
        double avg = sum / n;

        return PercentileMetric.newBuilder()
                .setP50Ms(Math.round(p50 * 100.0) / 100.0)
                .setP95Ms(Math.round(p95 * 100.0) / 100.0)
                .setP99Ms(Math.round(p99 * 100.0) / 100.0)
                .setAvgMs(Math.round(avg * 100.0) / 100.0)
                .setMaxMs(Math.round(max * 100.0) / 100.0)
                .setTotalRequests(n)
                .setErrorCount(errorCount)
                .build();
    }

    public static class ServiceRollingBuffer {
        private final String serviceName;
        private final String operationName;
        private final DoubleArrayList[] buckets = new DoubleArrayList[WINDOW_SIZE_SECONDS];
        private final long[] bucketTimestamps = new long[WINDOW_SIZE_SECONDS];
        private final long[] errorCounts = new long[WINDOW_SIZE_SECONDS];
        private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

        public ServiceRollingBuffer(String serviceName, String operationName) {
            this.serviceName = serviceName;
            this.operationName = operationName;
            for (int i = 0; i < WINDOW_SIZE_SECONDS; i++) {
                buckets[i] = new DoubleArrayList(64);
                bucketTimestamps[i] = 0L;
                errorCounts[i] = 0L;
            }
        }

        public void record(double latencyMs) {
            record(latencyMs, false);
        }

        public void record(double latencyMs, boolean isError) {
            long currentSec = System.currentTimeMillis() / 1000L;
            int idx = (int) (currentSec % WINDOW_SIZE_SECONDS);

            rwLock.writeLock().lock();
            try {
                if (bucketTimestamps[idx] != currentSec) {
                    buckets[idx].clear();
                    errorCounts[idx] = 0L;
                    bucketTimestamps[idx] = currentSec;
                }
                buckets[idx].add(latencyMs);
                if (isError) {
                    errorCounts[idx]++;
                }
            } finally {
                rwLock.writeLock().unlock();
            }
        }

        public long collectSamplesInto(DoubleArrayList target, long currentSec) {
            long errors = 0;
            rwLock.readLock().lock();
            try {
                for (int i = 0; i < WINDOW_SIZE_SECONDS; i++) {
                    if (currentSec - bucketTimestamps[i] < WINDOW_SIZE_SECONDS && bucketTimestamps[i] > 0) {
                        target.addAll(buckets[i]);
                        errors += errorCounts[i];
                    }
                }
            } finally {
                rwLock.readLock().unlock();
            }
            return errors;
        }

        public PercentileMetric calculatePercentiles() {
            long currentSec = System.currentTimeMillis() / 1000L;
            DoubleArrayList allSamples = new DoubleArrayList(512);
            long totalErrors = collectSamplesInto(allSamples, currentSec);
            return buildPercentileMetric(allSamples, totalErrors);
        }
    }
}
