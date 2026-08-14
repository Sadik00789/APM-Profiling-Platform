package com.apm.agent.emitter;

import com.apm.contracts.trace.v1.BatchSpanRequest;
import com.apm.contracts.trace.v1.SpanRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

@Slf4j
@Component
public class OtlpSpanEmitter {

    private final WebClient webClient;

    public OtlpSpanEmitter(
            @Value("${apm.agent.collector-url:http://localhost:8080}") String collectorUrl) {
        this.webClient = WebClient.builder()
                .baseUrl(collectorUrl)
                .build();
    }

    public void emitSpans(List<SpanRecord> spans) {
        if (spans == null || spans.isEmpty()) return;

        BatchSpanRequest request = BatchSpanRequest.newBuilder()
                .addAllSpans(spans)
                .setSourceAgentId("synthetic-agent-cluster-1")
                .setSentTimeUnixNano(System.currentTimeMillis() * 1_000_000L)
                .build();

        webClient.post()
                .uri("/v1/traces")
                .contentType(MediaType.valueOf("application/x-protobuf"))
                .bodyValue(request.toByteArray())
                .retrieve()
                .bodyToMono(String.class)
                .subscribe(
                        resp -> log.debug("Trace batch emitted: {} spans", spans.size()),
                        err -> log.debug("Collector unavailable (collector may be booting up): {}", err.getMessage())
                );
    }
}
