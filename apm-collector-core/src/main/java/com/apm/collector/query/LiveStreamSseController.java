package com.apm.collector.query;

import com.apm.collector.config.RedisStreamConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.ReactiveSubscription;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/stream")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class LiveStreamSseController {

    private final ReactiveRedisTemplate<String, String> redisTemplate;

    @GetMapping(value = "/live", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamLiveTelemetry() {
        String clientId = UUID.randomUUID().toString().substring(0, 8);
        log.info("Client connected to SSE live telemetry stream: {}", clientId);

        // 1. Stream span events
        Flux<ServerSentEvent<String>> spanEvents = redisTemplate.listenToChannel(RedisStreamConfig.SPAN_LIVE_CHANNEL)
                .map(ReactiveSubscription.Message::getMessage)
                .map(payload -> ServerSentEvent.<String>builder()
                        .id(UUID.randomUUID().toString())
                        .event("span")
                        .data(payload)
                        .build())
                .onErrorResume(e -> {
                    log.debug("SSE span stream error: {}", e.getMessage());
                    return Flux.empty();
                });

        // 2. Stream anomaly alert events
        Flux<ServerSentEvent<String>> anomalyEvents = redisTemplate.listenToChannel(RedisStreamConfig.ANOMALY_ALERT_CHANNEL)
                .map(ReactiveSubscription.Message::getMessage)
                .map(payload -> ServerSentEvent.<String>builder()
                        .id(UUID.randomUUID().toString())
                        .event("anomaly")
                        .data(payload)
                        .build())
                .onErrorResume(e -> {
                    log.debug("SSE anomaly stream error: {}", e.getMessage());
                    return Flux.empty();
                });

        // 3. Heartbeat keep-alive every 15 seconds
        Flux<ServerSentEvent<String>> heartbeat = Flux.interval(Duration.ofSeconds(15))
                .map(seq -> ServerSentEvent.<String>builder()
                        .id(String.valueOf(seq))
                        .event("ping")
                        .data("{\"type\":\"ping\",\"timestamp\":" + System.currentTimeMillis() + "}")
                        .build());

        return Flux.merge(spanEvents, anomalyEvents, heartbeat)
                .doFinally(signalType -> log.info("Client {} disconnected from SSE stream (signal: {})", clientId, signalType));
    }
}
