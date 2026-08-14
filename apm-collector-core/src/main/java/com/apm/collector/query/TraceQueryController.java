package com.apm.collector.query;

import com.apm.collector.engine.tracing.TraceDagReconstructor;
import com.apm.contracts.trace.v1.SpanKind;
import com.apm.contracts.trace.v1.SpanRecord;
import com.apm.contracts.trace.v1.StatusCode;
import com.apm.contracts.trace.v1.TraceTreeResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/traces")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class TraceQueryController {

    private final DataSource dataSource;
    private final TraceDagReconstructor dagReconstructor;

    @GetMapping
    public Mono<ResponseEntity<Map<String, Object>>> searchSpans(
            @RequestParam(required = false) String service,
            @RequestParam(required = false) String operation,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long minDurationMs,
            @RequestParam(required = false) Long maxDurationMs,
            @RequestParam(required = false) Long fromTimestampMs,
            @RequestParam(required = false) Long toTimestampMs,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {

        return Mono.fromCallable(() -> {
            StringBuilder sql = new StringBuilder("""
                SELECT start_time, end_time, trace_id, span_id, parent_span_id, service_name,
                       operation_name, duration_nano, status_code, attributes
                FROM default.traces_spans
                WHERE 1=1
                """);

            List<Object> params = new ArrayList<>();

            if (service != null && !service.isBlank() && !"all".equalsIgnoreCase(service)) {
                sql.append(" AND service_name = ?");
                params.add(service);
            }
            if (operation != null && !operation.isBlank()) {
                sql.append(" AND operation_name = ?");
                params.add(operation);
            }
            if (status != null && !status.isBlank() && !"all".equalsIgnoreCase(status)) {
                sql.append(" AND status_code = ?");
                params.add(status.toUpperCase());
            }
            if (minDurationMs != null && minDurationMs > 0) {
                sql.append(" AND duration_nano >= ?");
                params.add(minDurationMs * 1_000_000L);
            }
            if (maxDurationMs != null && maxDurationMs > 0) {
                sql.append(" AND duration_nano <= ?");
                params.add(maxDurationMs * 1_000_000L);
            }
            if (fromTimestampMs != null && fromTimestampMs > 0) {
                sql.append(" AND start_time >= ?");
                params.add(new Timestamp(fromTimestampMs));
            }
            if (toTimestampMs != null && toTimestampMs > 0) {
                sql.append(" AND start_time <= ?");
                params.add(new Timestamp(toTimestampMs));
            }

            sql.append(" ORDER BY start_time DESC LIMIT ? OFFSET ?");
            params.add(limit);
            params.add(offset);

            List<Map<String, Object>> spans = new ArrayList<>();

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql.toString())) {

                for (int i = 0; i < params.size(); i++) {
                    ps.setObject(i + 1, params.get(i));
                }

                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> item = new HashMap<>();
                        Timestamp ts = rs.getTimestamp("start_time");
                        item.put("timestamp", ts != null ? ts.getTime() : System.currentTimeMillis());
                        item.put("traceId", rs.getString("trace_id"));
                        item.put("spanId", rs.getString("span_id"));
                        item.put("parentSpanId", rs.getString("parent_span_id"));
                        item.put("serviceName", rs.getString("service_name"));
                        item.put("operationName", rs.getString("operation_name"));
                        long durationNano = rs.getLong("duration_nano");
                        item.put("durationNano", durationNano);
                        item.put("durationMs", Math.round((durationNano / 1_000_000.0) * 100.0) / 100.0);
                        item.put("statusCode", rs.getString("status_code"));
                        item.put("attributes", rs.getObject("attributes"));
                        spans.add(item);
                    }
                }
            } catch (Exception e) {
                log.warn("ClickHouse trace query warning: {}", e.getMessage());
            }

            return ResponseEntity.ok(Map.<String, Object>of(
                    "spans", spans,
                    "count", spans.size(),
                    "limit", limit,
                    "offset", offset
            ));
        });
    }

    @GetMapping("/{traceId}")
    public Mono<ResponseEntity<Map<String, Object>>> getTraceTree(@PathVariable String traceId) {
        return Mono.fromCallable(() -> {
            String sql = """
                SELECT start_time, end_time, trace_id, span_id, parent_span_id, service_name,
                       operation_name, duration_nano, status_code, attributes
                FROM default.traces_spans
                WHERE trace_id = ?
                ORDER BY start_time ASC
                """;

            List<SpanRecord> spans = new ArrayList<>();

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, traceId);

                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Timestamp ts = rs.getTimestamp("start_time");
                        long startNano = (ts != null) ? ts.getTime() * 1_000_000L + ts.getNanos() % 1_000_000 : System.currentTimeMillis() * 1_000_000L;
                        long durationNano = rs.getLong("duration_nano");

                        String statusStr = rs.getString("status_code");
                        StatusCode code = "STATUS_CODE_ERROR".equalsIgnoreCase(statusStr) || "ERROR".equalsIgnoreCase(statusStr)
                                ? StatusCode.STATUS_CODE_ERROR : StatusCode.STATUS_CODE_OK;

                        @SuppressWarnings("unchecked")
                        Map<String, String> attrs = (rs.getObject("attributes") instanceof Map<?, ?> m)
                                ? (Map<String, String>) m
                                : Collections.emptyMap();

                        SpanRecord span = SpanRecord.newBuilder()
                                .setTraceId(rs.getString("trace_id"))
                                .setSpanId(rs.getString("span_id"))
                                .setParentSpanId(rs.getString("parent_span_id") != null ? rs.getString("parent_span_id") : "")
                                .setServiceName(rs.getString("service_name"))
                                .setOperationName(rs.getString("operation_name"))
                                .setStartTimeUnixNano(startNano)
                                .setEndTimeUnixNano(startNano + durationNano)
                                .setDurationNano(durationNano)
                                .setStatusCode(code)
                                .setSpanKind(SpanKind.SPAN_KIND_INTERNAL)
                                .putAllAttributes(attrs)
                                .build();
                        spans.add(span);
                    }
                }
            } catch (Exception e) {
                log.error("Failed to query trace spans for traceId {}: {}", traceId, e.getMessage());
            }

            if (spans.isEmpty()) {
                return ResponseEntity.ok(Map.<String, Object>of("traceId", traceId, "spans", Collections.emptyList(), "found", false));
            }

            TraceTreeResponse treeResponse = dagReconstructor.reconstruct(spans);

            Map<String, Object> response = new HashMap<>();
            response.put("traceId", treeResponse.getTraceId());
            response.put("totalDurationMs", treeResponse.getTotalDurationNano() / 1_000_000.0);
            response.put("totalSpans", treeResponse.getTotalSpans());
            response.put("criticalPathSpans", treeResponse.getCriticalPathSpans());
            response.put("errorCount", treeResponse.getErrorCount());
            response.put("servicesInvolved", treeResponse.getServicesInvolvedList());
            response.put("root", formatTreeNode(treeResponse.getRoot()));
            response.put("found", true);

            return ResponseEntity.ok(response);
        });
    }

    private Map<String, Object> formatTreeNode(com.apm.contracts.trace.v1.TraceTreeNode node) {
        if (node == null || !node.hasSpan()) return Collections.emptyMap();

        Map<String, Object> map = new LinkedHashMap<>();
        SpanRecord span = node.getSpan();
        map.put("spanId", span.getSpanId());
        map.put("parentSpanId", span.getParentSpanId());
        map.put("serviceName", span.getServiceName());
        map.put("operationName", span.getOperationName());
        map.put("startTimeUnixNano", span.getStartTimeUnixNano());
        map.put("durationMs", Math.round((span.getDurationNano() / 1_000_000.0) * 100.0) / 100.0);
        map.put("durationNano", span.getDurationNano());
        map.put("statusCode", span.getStatusCode().name());
        map.put("isCriticalPath", node.getIsCriticalPath());
        map.put("exclusiveTimePercent", node.getExclusiveTimePercent());
        map.put("depth", node.getDepth());
        map.put("attributes", span.getAttributesMap());

        List<Map<String, Object>> children = new ArrayList<>();
        for (com.apm.contracts.trace.v1.TraceTreeNode child : node.getChildrenList()) {
            children.add(formatTreeNode(child));
        }
        map.put("children", children);

        return map;
    }
}
