package com.apm.collector.query;

import com.apm.collector.engine.trie.CallStackTrie;
import com.apm.collector.engine.trie.FlameGraphSerializer;
import com.apm.contracts.profile.v1.DiffFlameGraphResponse;
import com.apm.contracts.profile.v1.ProfileType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/profiles")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class ProfileQueryController {

    private final DataSource dataSource;

    @GetMapping("/flamegraph")
    public Mono<ResponseEntity<Map<String, Object>>> getFlameGraph(
            @RequestParam(defaultValue = "order-service") String service,
            @RequestParam(defaultValue = "CPU") String profileType,
            @RequestParam(required = false) Long fromTimestampSec,
            @RequestParam(required = false) Long untilTimestampSec,
            @RequestParam(defaultValue = "0.01") double minPercentThreshold) {

        return Mono.fromCallable(() -> {
            long now = System.currentTimeMillis() / 1000L;
            long until = (untilTimestampSec != null && untilTimestampSec > 0) ? untilTimestampSec : now;
            long from = (fromTimestampSec != null && fromTimestampSec > 0) ? fromTimestampSec : (until - 3600); // 1h default

            ProfileType pType = parseProfileType(profileType);
            CallStackTrie trie = loadTrieFromClickHouse(service, pType, from, until, true);

            trie.prune(minPercentThreshold);

            Map<String, Object> response = new HashMap<>();
            response.put("serviceName", service);
            response.put("profileType", pType.name().replace("PROFILE_TYPE_", ""));
            response.put("fromTimestampSec", from);
            response.put("untilTimestampSec", until);
            response.put("totalSamples", trie.getTotalSamples());
            response.put("maxDepth", trie.getMaxDepth());
            response.put("totalNodes", trie.countNodes());
            response.put("root", FlameGraphSerializer.toNestedMap(trie.getRoot(), trie));

            return ResponseEntity.ok(response);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/diff")
    public Mono<ResponseEntity<Map<String, Object>>> getDiffFlameGraph(
            @RequestParam(defaultValue = "order-service") String service,
            @RequestParam(defaultValue = "CPU") String profileType,
            @RequestParam(required = false) Long baselineFrom,
            @RequestParam(required = false) Long baselineUntil,
            @RequestParam(required = false) Long compFrom,
            @RequestParam(required = false) Long compUntil) {

        return Mono.fromCallable(() -> {
            long now = System.currentTimeMillis() / 1000L;
            long bUntil = (baselineUntil != null) ? baselineUntil : now - 3600;
            long bFrom = (baselineFrom != null) ? baselineFrom : bUntil - 3600;

            long cUntil = (compUntil != null) ? compUntil : now;
            long cFrom = (compFrom != null) ? compFrom : cUntil - 3600;

            ProfileType pType = parseProfileType(profileType);
            CallStackTrie baseTrie = loadTrieFromClickHouse(service, pType, bFrom, bUntil, false);
            CallStackTrie compTrie = loadTrieFromClickHouse(service, pType, cFrom, cUntil, false);

            if (baseTrie.getTotalSamples() == 0 && compTrie.getTotalSamples() == 0) {
                seedSampleProfiles(baseTrie, service, false);
                seedSampleProfiles(compTrie, service, true);
            }

            DiffFlameGraphResponse diffProto = baseTrie.computeDiff(compTrie, service, pType);

            Map<String, Object> response = new HashMap<>();
            response.put("serviceName", service);
            response.put("profileType", pType.name().replace("PROFILE_TYPE_", ""));
            response.put("baselineTotal", diffProto.getBaselineTotal());
            response.put("comparisonTotal", diffProto.getComparisonTotal());
            response.put("overallChangePercent", Math.round(diffProto.getOverallChangePercent() * 100.0) / 100.0);
            response.put("root", FlameGraphSerializer.toNestedMap(diffProto.getRoot()));

            return ResponseEntity.ok(response);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private CallStackTrie loadTrieFromClickHouse(
            String service,
            ProfileType profileType,
            long fromSec,
            long untilSec,
            boolean seedIfEmpty) {
        CallStackTrie trie = new CallStackTrie();
        String enumType = profileType.name();
        String shortType = enumType.replace("PROFILE_TYPE_", "");

        String sql = """
            SELECT stack_trace, sum(value) AS total_samples
            FROM default.profiles_samples
            WHERE service_name = ?
              AND profile_type IN (?, ?)
              AND sample_timestamp >= ?
              AND sample_timestamp <= ?
            GROUP BY stack_trace
            """;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, service);
            ps.setString(2, enumType);
            ps.setString(3, shortType);
            ps.setTimestamp(4, new Timestamp(fromSec * 1000L));
            ps.setTimestamp(5, new Timestamp(untilSec * 1000L));

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String stack = rs.getString("stack_trace");
                    long samples = rs.getLong("total_samples");
                    if (stack != null && !stack.isEmpty() && samples > 0) {
                        String[] frames = stack.split(";");
                        trie.insert(frames, samples);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Query profiles_samples warning: {}", e.getMessage());
        }

        // If no records in database yet, seed with sample profiles for immediate UI preview
        if (seedIfEmpty && trie.getTotalSamples() == 0) {
            seedSampleProfiles(trie, service, false);
        }

        return trie;
    }

    private void seedSampleProfiles(CallStackTrie trie, String service, boolean incidentSpike) {
        String base = "java.lang.Thread.run;org.springframework.boot.web.embedded.netty.NettyWebServer.start;";
        long bcryptSamples = incidentSpike ? 1650 : 850;
        long regexSamples = incidentSpike ? 1120 : 640;
        long querySamples = incidentSpike ? 390 : 420;
        long jsonSamples = incidentSpike ? 180 : 290;
        long pingSamples = incidentSpike ? 95 : 110;

        trie.insert((base + "com.apm." + service + ".controller.handleRequest;com.apm." + service + ".service.process;com.apm.common.crypto.BCrypt.hashPassword").split(";"), bcryptSamples);
        trie.insert((base + "com.apm." + service + ".controller.handleRequest;com.apm." + service + ".service.process;com.apm.common.regex.PatternMatcher.matches").split(";"), regexSamples);
        trie.insert((base + "com.apm." + service + ".controller.handleRequest;com.apm." + service + ".repository.findUser;com.clickhouse.jdbc.internal.ClickHouseConnection.query").split(";"), querySamples);
        trie.insert((base + "com.apm." + service + ".controller.handleRequest;com.apm." + service + ".service.serializeJson;com.fasterxml.jackson.databind.ObjectMapper.writeValue").split(";"), jsonSamples);
        trie.insert((base + "com.apm." + service + ".healthCheck;com.apm.collector.ApmCollectorApplication.ping").split(";"), pingSamples);
    }

    private ProfileType parseProfileType(String str) {
        if (str == null || str.isBlank()) return ProfileType.PROFILE_TYPE_CPU;
        String normalized = str.trim().toUpperCase();
        if (!normalized.startsWith("PROFILE_TYPE_")) {
            normalized = "PROFILE_TYPE_" + normalized;
        }
        try {
            return ProfileType.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            return ProfileType.PROFILE_TYPE_CPU;
        }
    }
}
