package com.apm.collector.ingestion.otlp;

import com.apm.collector.config.DisruptorConfig;
import com.apm.collector.config.RedisStreamConfig;
import com.apm.collector.engine.anomaly.SlidingWindowLatencyTracker;
import com.apm.collector.engine.sampling.TailBasedSamplingFilter;
import com.apm.contracts.trace.v1.BatchSpanRequest;
import com.apm.contracts.trace.v1.SpanRecord;
import com.apm.contracts.trace.v1.StatusCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lmax.disruptor.RingBuffer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/v1/traces")
public class OtlpHttpTraceHandler {

    private final OtlpSpanDecoder spanDecoder;
    private final TailBasedSamplingFilter samplingFilter;
    private final SlidingWindowLatencyTracker latencyTracker;
    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final RingBuffer<DisruptorConfig.SpanEvent> spanRingBuffer;

    @Autowired
    public OtlpHttpTraceHandler(
            OtlpSpanDecoder spanDecoder,
            TailBasedSamplingFilter samplingFilter,
            SlidingWindowLatencyTracker latencyTracker,
            ReactiveRedisTemplate<String, String> redisTemplate,
            ObjectMapper objectMapper,
            @Nullable RingBuffer<DisruptorConfig.SpanEvent> spanRingBuffer) {
        this.spanDecoder = spanDecoder;
        this.samplingFilter = samplingFilter;
        this.latencyTracker = latencyTracker;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.spanRingBuffer = spanRingBuffer;
    }

    @PostMapping(consumes = {MediaType.APPLICATION_JSON_VALUE, "application/x-protobuf", "*/*"})
    public Mono<ResponseEntity<Map<String, Object>>> ingestTraces(
            @RequestBody(required = false) byte[] rawBytes,
            @RequestHeader(value = "Content-Type", required = false) String contentType) {

        return Mono.fromCallable(() -> {
            if (rawBytes == null || rawBytes.length == 0) {
                return ResponseEntity.ok(Map.<String, Object>of("status", "empty_payload", "accepted", 0));
            }

            List<SpanRecord> spans;
            if (contentType != null && contentType.contains("x-protobuf")) {
                try {
                    BatchSpanRequest batch = BatchSpanRequest.parseFrom(rawBytes);
                    spans = batch.getSpansList();
                } catch (Exception e) {
                    log.warn("Protobuf decode failed, attempting JSON fallback: {}", e.getMessage());
                    spans = spanDecoder.decodeJson(new String(rawBytes, StandardCharsets.UTF_8));
                }
            } else {
                spans = spanDecoder.decodeJson(new String(rawBytes, StandardCharsets.UTF_8));
            }

            if (spans.isEmpty()) {
                return ResponseEntity.ok(Map.<String, Object>of("status", "no_spans_decoded", "accepted", 0));
            }

            // Ingest spans via LMAX Disruptor (with direct fallback), latency tracker, and live Redis broadcast
            for (SpanRecord span : spans) {
                if (spanRingBuffer == null || !spanRingBuffer.tryPublishEvent((event, seq) -> event.setSpan(span))) {
                    samplingFilter.acceptSpan(span);
                }
                latencyTracker.recordSpan(
                        span.getServiceName(),
                        span.getOperationName(),
                        span.getDurationNano() / 1_000_000.0,
                        span.getStatusCode() == StatusCode.STATUS_CODE_ERROR
                );
                publishSpanToLiveFeed(span);
            }

            return ResponseEntity.ok(Map.<String, Object>of(
                    "status", "success",
                    "accepted", spans.size(),
                    "rejected", 0
            ));
        }).onErrorResume(ex -> {
            log.error("Error during trace ingestion: {}", ex.getMessage(), ex);
            String msg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
            return Mono.just(ResponseEntity.badRequest().body(Map.<String, Object>of(
                    "status", "error",
                    "message", msg
            )));
        });
    }

    private void publishSpanToLiveFeed(SpanRecord span) {
        if (redisTemplate == null) return;
        try {
            Map<String, Object> event = Map.of(
                    "traceId", span.getTraceId(),
                    "spanId", span.getSpanId(),
                    "parentSpanId", span.getParentSpanId(),
                    "serviceName", span.getServiceName(),
                    "operationName", span.getOperationName(),
                    "durationMs", span.getDurationNano() / 1_000_000.0,
                    "statusCode", span.getStatusCode().name(),
                    "timestamp", span.getStartTimeUnixNano()
            );
            String payload = objectMapper.writeValueAsString(event);
            redisTemplate.convertAndSend(RedisStreamConfig.SPAN_LIVE_CHANNEL, payload)
                    .subscribe(c -> {}, err -> log.debug("Redis broadcast error (non-fatal): {}", err.getMessage()));
        } catch (Exception e) {
            log.debug("Redis broadcast error (non-fatal): {}", e.getMessage());
        }
    }
}
