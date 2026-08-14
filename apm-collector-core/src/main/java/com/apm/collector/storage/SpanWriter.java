package com.apm.collector.storage;

import com.apm.contracts.trace.v1.SpanRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class SpanWriter {

    private final DataSource dataSource;

    private static final String INSERT_SPAN_SQL = """
        INSERT INTO default.traces_spans
        (trace_id, span_id, parent_span_id, service_name, operation_name, span_kind, start_time, end_time, duration_nano, status_code, status_message, attributes)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

    public int writeBatch(List<SpanRecord> spans) {
        if (spans == null || spans.isEmpty()) {
            return 0;
        }

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(INSERT_SPAN_SQL)) {

            for (SpanRecord span : spans) {
                long startNano = span.getStartTimeUnixNano();
                long endNano = span.getEndTimeUnixNano() > 0 ? span.getEndTimeUnixNano() : (startNano + span.getDurationNano());

                Timestamp startTs = new Timestamp(startNano / 1_000_000L);
                startTs.setNanos((int) (startNano % 1_000_000_000L));

                Timestamp endTs = new Timestamp(endNano / 1_000_000L);
                endTs.setNanos((int) (endNano % 1_000_000_000L));

                ps.setString(1, span.getTraceId());
                ps.setString(2, span.getSpanId());
                ps.setString(3, span.getParentSpanId() != null ? span.getParentSpanId() : "");
                ps.setString(4, span.getServiceName());
                ps.setString(5, span.getOperationName());
                ps.setString(6, span.getSpanKind().name());
                ps.setTimestamp(7, startTs);
                ps.setTimestamp(8, endTs);
                ps.setLong(9, span.getDurationNano());
                ps.setString(10, span.getStatusCode().name());
                ps.setString(11, span.getStatusMessage() != null ? span.getStatusMessage() : "");

                Map<String, String> attrs = span.getAttributesMap();
                ps.setObject(12, attrs);

                ps.addBatch();
            }

            int[] results = ps.executeBatch();
            log.debug("Successfully flushed batch of {} spans to ClickHouse", results.length);
            return results.length;
        } catch (SQLException e) {
            log.error("ClickHouse batch span insert error: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to persist span batch to ClickHouse", e);
        }
    }
}
