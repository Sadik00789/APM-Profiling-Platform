package com.apm.agent.emitter;

import com.apm.agent.generator.JfrCpuLoadInjector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Random;

@Slf4j
@Component
public class PyroscopeProfileEmitter {

    private final JfrCpuLoadInjector loadInjector;
    private final WebClient webClient;
    private final Random random = new Random();

    private final List<String> services = List.of(
            "order-service", "payment-service", "api-gateway", "inventory-service"
    );

    public PyroscopeProfileEmitter(
            JfrCpuLoadInjector loadInjector,
            @Value("${apm.agent.collector-url:http://localhost:8080}") String collectorUrl) {
        this.loadInjector = loadInjector;
        this.webClient = WebClient.builder()
                .baseUrl(collectorUrl)
                .build();
    }

    @Scheduled(fixedRateString = "${apm.agent.profiling.emit-interval-ms:8000}")
    public void emitProfiles() {
        for (String service : services) {
            boolean injectSpike = "order-service".equals(service) && (random.nextInt(100) < 25);
            String foldedProfile = loadInjector.generateFoldedProfile(service, injectSpike);

            webClient.post()
                    .uri(uriBuilder -> uriBuilder
                            .path("/ingest")
                            .queryParam("name", service + ".cpu")
                            .queryParam("units", "samples")
                            .queryParam("sampleRate", 100)
                            .queryParam("until", System.currentTimeMillis() / 1000L)
                            .build())
                    .contentType(MediaType.TEXT_PLAIN)
                    .bodyValue(foldedProfile)
                    .retrieve()
                    .bodyToMono(String.class)
                    .subscribe(
                            resp -> log.debug("Pushed folded profile for {}: spike={}", service, injectSpike),
                            err -> log.debug("Collector profiling ingestion unavailable (non-fatal): {}", err.getMessage())
                    );
        }
    }
}
