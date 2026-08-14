package com.apm.collector.ingestion.otlp;

import com.apm.collector.config.RedisStreamConfig;
import com.apm.collector.engine.anomaly.SlidingWindowLatencyTracker;
import com.apm.collector.engine.sampling.TailBasedSamplingFilter;
import com.apm.contracts.trace.v1.BatchSpanRequest;
import com.apm.contracts.trace.v1.SpanRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/v1/traces")
@RequiredArgsConstructor
public class OtlpHttpTraceHandler {

    private final OtlpSpanDecoder spanDecoder;
    private final TailBasedSamplingFilter samplingFilter;
    private final SlidingWindowLatencyTracker latencyTracker;
    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

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
                    spans = spanDecoder.decodeJson(new String(rawBytes));
                }
            } else {
                spans = spanDecoder.decodeJson(new String(rawBytes));
            }

            if (spans.isEmpty()) {
                return ResponseEntity.ok(Map.<String, Object>of("status", "no_spans_decoded", "accepted", 0));
            }

            // Ingest spans into tail-based sampling filter, latency tracker, and live Redis broadcast
            for (SpanRecord span : spans) {
                samplingFilter.acceptSpan(span);
                latencyTracker.recordLatency(span.getServiceName(), span.getOperationName(), span.getDurationNano() / 1_000_000.0);
                publishSpanToLiveFeed(span);
            }

            return ResponseEntity.ok(Map.<String, Object>of(
                    "status", "success",
                    "accepted", spans.size(),
                    "rejected", 0
            ));
        }).onErrorResume(ex -> {
            log.error("Error during trace ingestion: {}", ex.getMessage(), ex);
            return Mono.just(ResponseEntity.badRequest().body(Map.<String, Object>of(
                    "status", "error",
                    "message", ex.getMessage()
            )));
        });
    }

    private void publishSpanToLiveFeed(SpanRecord span) {
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
            redisTemplate.convertAndSend(RedisStreamConfig.SPAN_LIVE_CHANNEL, payload).subscribe();
        } catch (Exception e) {
            log.debug("Redis broadcast error (non-fatal): {}", e.getMessage());
        }
    }
}
