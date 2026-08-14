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
        if (serviceName == null || serviceName.isBlank()) return;
        String key = serviceName + ":" + (operationName != null ? operationName : "default");

        ServiceRollingBuffer buffer = serviceBuffers.computeIfAbsent(key, k -> new ServiceRollingBuffer(serviceName, operationName));
        buffer.record(latencyMs);

        // Check for latency anomaly
        if (latencyMs >= p99AlertThresholdMs) {
            checkAndEmitAnomaly(serviceName, operationName, latencyMs, "P99_THRESHOLD_EXCEEDED");
        }
    }

    public PercentileMetric getPercentiles(String serviceName, String operationName) {
        String key = serviceName + ":" + (operationName != null ? operationName : "default");
        ServiceRollingBuffer buffer = serviceBuffers.get(key);
        if (buffer == null) {
            return PercentileMetric.getDefaultInstance();
        }
        return buffer.calculatePercentiles();
    }

    public List<ServiceMetricsSummary> getAllServiceSummaries() {
        Map<String, List<ServiceRollingBuffer>> byService = new HashMap<>();
        for (ServiceRollingBuffer buf : serviceBuffers.values()) {
            byService.computeIfAbsent(buf.serviceName, k -> new ArrayList<>()).add(buf);
        }

        List<ServiceMetricsSummary> summaries = new ArrayList<>();
        for (Map.Entry<String, List<ServiceRollingBuffer>> entry : byService.entrySet()) {
            String serviceName = entry.getKey();
            List<ServiceRollingBuffer> buffers = entry.getValue();

            long totalReqs = 0;
            double sumP50 = 0, sumP95 = 0, sumP99 = 0, sumAvg = 0, maxMs = 0;

            for (ServiceRollingBuffer buf : buffers) {
                PercentileMetric m = buf.calculatePercentiles();
                totalReqs += m.getTotalRequests();
                sumP50 += m.getP50Ms();
                sumP95 += m.getP95Ms();
                sumP99 += m.getP99Ms();
                sumAvg += m.getAvgMs();
                maxMs = Math.max(maxMs, m.getMaxMs());
            }

            int n = Math.max(1, buffers.size());
            double rps = (double) totalReqs / WINDOW_SIZE_SECONDS;

            PercentileMetric aggregateMetric = PercentileMetric.newBuilder()
                    .setP50Ms(Math.round(sumP50 / n * 100.0) / 100.0)
                    .setP95Ms(Math.round(sumP95 / n * 100.0) / 100.0)
                    .setP99Ms(Math.round(sumP99 / n * 100.0) / 100.0)
                    .setAvgMs(Math.round(sumAvg / n * 100.0) / 100.0)
                    .setMaxMs(Math.round(maxMs * 100.0) / 100.0)
                    .setTotalRequests(totalReqs)
                    .build();

            summaries.add(ServiceMetricsSummary.newBuilder()
                    .setServiceName(serviceName)
                    .setMetrics(aggregateMetric)
                    .setRps(Math.round(rps * 10.0) / 10.0)
                    .setErrorRatePercent(0.0)
                    .setActiveInstances(1)
                    .build());
        }

        return summaries;
    }

    private void checkAndEmitAnomaly(String serviceName, String operationName, double latencyMs, String reason) {
        try {
            Map<String, Object> alert = Map.of(
                    "type", "LATENCY_ANOMALY",
                    "serviceName", serviceName,
                    "operationName", operationName,
                    "observedLatencyMs", latencyMs,
                    "reason", reason,
                    "timestamp", System.currentTimeMillis()
            );
            String json = objectMapper.writeValueAsString(alert);
            redisTemplate.convertAndSend(RedisStreamConfig.ANOMALY_ALERT_CHANNEL, json).subscribe();
        } catch (Exception e) {
            log.debug("Failed to send anomaly alert to Redis: {}", e.getMessage());
        }
    }

    public static class ServiceRollingBuffer {
        private final String serviceName;
        private final String operationName;
        private final DoubleArrayList[] buckets = new DoubleArrayList[WINDOW_SIZE_SECONDS];
        private final long[] bucketTimestamps = new long[WINDOW_SIZE_SECONDS];
        private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

        public ServiceRollingBuffer(String serviceName, String operationName) {
            this.serviceName = serviceName;
            this.operationName = operationName;
            for (int i = 0; i < WINDOW_SIZE_SECONDS; i++) {
                buckets[i] = new DoubleArrayList(64);
                bucketTimestamps[i] = 0L;
            }
        }

        public void record(double latencyMs) {
            long currentSec = System.currentTimeMillis() / 1000L;
            int idx = (int) (currentSec % WINDOW_SIZE_SECONDS);

            rwLock.writeLock().lock();
            try {
                if (bucketTimestamps[idx] != currentSec) {
                    buckets[idx].clear();
                    bucketTimestamps[idx] = currentSec;
                }
                buckets[idx].add(latencyMs);
            } finally {
                rwLock.writeLock().unlock();
            }
        }

        public PercentileMetric calculatePercentiles() {
            long currentSec = System.currentTimeMillis() / 1000L;
            DoubleArrayList allSamples = new DoubleArrayList(512);

            rwLock.readLock().lock();
            try {
                for (int i = 0; i < WINDOW_SIZE_SECONDS; i++) {
                    if (currentSec - bucketTimestamps[i] < WINDOW_SIZE_SECONDS && bucketTimestamps[i] > 0) {
                        allSamples.addAll(buckets[i]);
                    }
                }
            } finally {
                rwLock.readLock().unlock();
            }

            if (allSamples.isEmpty()) {
                return PercentileMetric.getDefaultInstance();
            }

            double[] arr = allSamples.toDoubleArray();
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
                    .build();
        }
    }
}
