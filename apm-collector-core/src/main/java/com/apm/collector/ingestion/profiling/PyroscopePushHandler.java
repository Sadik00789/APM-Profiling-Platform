package com.apm.collector.ingestion.profiling;

import com.apm.collector.storage.ClickHouseBatchFlusher;
import com.apm.contracts.profile.v1.ProfileSample;
import com.apm.contracts.profile.v1.ProfileType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping({"/ingest", "/api/v1/profiles/ingest"})
@RequiredArgsConstructor
public class PyroscopePushHandler {

    private final FoldedStackParser foldedStackParser;
    private final ClickHouseBatchFlusher batchFlusher;

    @PostMapping(consumes = {MediaType.TEXT_PLAIN_VALUE, MediaType.APPLICATION_OCTET_STREAM_VALUE, "*/*"})
    public Mono<ResponseEntity<Map<String, Object>>> ingestFoldedProfile(
            @RequestBody(required = false) String rawBody,
            @RequestParam(value = "name", defaultValue = "unknown-service.cpu") String serviceParam,
            @RequestParam(value = "from", required = false) Long fromTimestampSec,
            @RequestParam(value = "until", required = false) Long untilTimestampSec,
            @RequestParam(value = "units", defaultValue = "samples") String units,
            @RequestParam(value = "sampleRate", defaultValue = "100") Integer sampleRate) {

        return Mono.fromCallable(() -> {
            if (rawBody == null || rawBody.isBlank()) {
                return ResponseEntity.ok(Map.<String, Object>of("status", "empty_payload", "samples_ingested", 0));
            }

            // Parse service name and profile type from "serviceName.profileType{tags...}"
            String serviceName = parseServiceName(serviceParam);
            ProfileType profileType = parseProfileType(serviceParam);
            long timestampSec = (untilTimestampSec != null) ? untilTimestampSec : System.currentTimeMillis() / 1000L;

            List<FoldedStackParser.ParsedFoldedSample> parsedSamples = foldedStackParser.parse(rawBody);

            for (FoldedStackParser.ParsedFoldedSample sample : parsedSamples) {
                ProfileSample profileSample = ProfileSample.newBuilder()
                        .setServiceName(serviceName)
                        .setProfileType(profileType)
                        .setTimestampUnixSec(timestampSec)
                        .addAllStackFrames(List.of(sample.frames()))
                        .setSampleCount(sample.sampleCount())
                        .build();

                batchFlusher.enqueueProfileSample(profileSample, sample.rawLine());
            }

            return ResponseEntity.ok(Map.<String, Object>of(
                    "status", "success",
                    "service", serviceName,
                    "profile_type", profileType.name(),
                    "samples_ingested", parsedSamples.size()
            ));
        }).onErrorResume(ex -> {
            log.error("Failed to ingest profile samples: {}", ex.getMessage(), ex);
            return Mono.just(ResponseEntity.badRequest().body(Map.<String, Object>of(
                    "status", "error",
                    "message", ex.getMessage()
            )));
        });
    }

    private String parseServiceName(String nameParam) {
        if (nameParam == null || nameParam.isEmpty()) return "unknown-service";
        String clean = nameParam.split("\\{")[0]; // remove Pyroscope tags {key=val}
        int dotIdx = clean.indexOf('.');
        return dotIdx > 0 ? clean.substring(0, dotIdx) : clean;
    }

    private ProfileType parseProfileType(String nameParam) {
        if (nameParam == null) return ProfileType.PROFILE_TYPE_CPU;
        String lower = nameParam.toLowerCase();
        if (lower.contains("alloc") || lower.contains("memory") || lower.contains("heap")) {
            return ProfileType.PROFILE_TYPE_ALLOC_SPACE;
        }
        if (lower.contains("lock") || lower.contains("mutex")) {
            return ProfileType.PROFILE_TYPE_LOCK_TIME;
        }
        if (lower.contains("wall")) {
            return ProfileType.PROFILE_TYPE_WALL;
        }
        return ProfileType.PROFILE_TYPE_CPU;
    }
}
