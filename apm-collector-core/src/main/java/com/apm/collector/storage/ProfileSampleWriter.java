package com.apm.collector.storage;

import com.apm.contracts.profile.v1.ProfileSample;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProfileSampleWriter {

    private final DataSource dataSource;

    private static final String INSERT_PROFILE_SQL = """
        INSERT INTO default.profiles_samples
        (service_name, profile_type, sample_timestamp, duration_nano, value, unit, stack_trace, labels)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """;

    public record ProfileSampleEntry(ProfileSample sample, String stackTraceString) {}

    public int writeBatch(List<ProfileSampleEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return 0;
        }

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(INSERT_PROFILE_SQL)) {

            for (ProfileSampleEntry entry : entries) {
                ProfileSample sample = entry.sample();
                Timestamp ts = new Timestamp(sample.getTimestampUnixSec() * 1000L);

                ps.setString(1, sample.getServiceName());
                ps.setString(2, sample.getProfileType().name());
                ps.setTimestamp(3, ts);
                ps.setLong(4, 0L); // duration_nano
                ps.setLong(5, sample.getSampleCount()); // value
                ps.setString(6, "samples"); // unit

                String stack = (entry.stackTraceString() != null && !entry.stackTraceString().isEmpty())
                        ? entry.stackTraceString()
                        : String.join(";", sample.getStackFramesList());

                ps.setString(7, stack);
                ps.setObject(8, Collections.emptyMap()); // labels

                ps.addBatch();
            }

            int[] results = ps.executeBatch();
            log.debug("Successfully flushed batch of {} profile samples to ClickHouse", results.length);
            return results.length;
        } catch (SQLException e) {
            log.error("ClickHouse batch profile sample insert error: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to persist profile batch to ClickHouse", e);
        }
    }
}
