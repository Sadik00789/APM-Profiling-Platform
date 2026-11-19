package com.apm.collector;

import com.apm.collector.storage.ClickHouseBatchFlusher;
import com.apm.collector.storage.ProfileSampleWriter;
import com.apm.collector.storage.SpanWriter;
import com.apm.contracts.profile.v1.ProfileSample;
import com.apm.contracts.profile.v1.ProfileType;
import com.apm.contracts.trace.v1.SpanRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class ClickHouseBatchFlusherTest {

    @Test
    @DisplayName("Should drain all queued spans and profiles across multiple batches on shutdown")
    void testMultiBatchDrainOnShutdown() {
        SpanWriter spanWriter = mock(SpanWriter.class);
        ProfileSampleWriter profileWriter = mock(ProfileSampleWriter.class);

        List<Integer> spanBatchSizes = new ArrayList<>();
        when(spanWriter.writeBatch(anyList())).thenAnswer(inv -> {
            List<?> batch = inv.getArgument(0);
            spanBatchSizes.add(batch.size());
            return batch.size();
        });

        List<Integer> profileBatchSizes = new ArrayList<>();
        when(profileWriter.writeBatch(anyList())).thenAnswer(inv -> {
            List<?> batch = inv.getArgument(0);
            profileBatchSizes.add(batch.size());
            return batch.size();
        });

        ClickHouseBatchFlusher flusher = new ClickHouseBatchFlusher(spanWriter, profileWriter, 100, 2);

        for (int i = 0; i < 5; i++) {
            flusher.enqueueSpan(SpanRecord.newBuilder().setTraceId("t").setSpanId("s-" + i).build());
            flusher.enqueueProfileSample(
                    ProfileSample.newBuilder()
                            .setServiceName("svc")
                            .setProfileType(ProfileType.PROFILE_TYPE_CPU)
                            .addStackFrames("main")
                            .setSampleCount(10)
                            .build(),
                    "main"
            );
        }

        flusher.onShutdown();

        assertEquals(5, spanBatchSizes.stream().mapToInt(Integer::intValue).sum());
        assertEquals(5, profileBatchSizes.stream().mapToInt(Integer::intValue).sum());
    }

    @Test
    @DisplayName("Should recover safely when writer throws exception during flush")
    void testWriterExceptionDoesNotBreakSubsequentFlushes() {
        SpanWriter spanWriter = mock(SpanWriter.class);
        ProfileSampleWriter profileWriter = mock(ProfileSampleWriter.class);

        when(spanWriter.writeBatch(anyList()))
                .thenThrow(new RuntimeException("ClickHouse unavailable"))
                .thenReturn(1);

        ClickHouseBatchFlusher flusher = new ClickHouseBatchFlusher(spanWriter, profileWriter, 50, 10);

        flusher.enqueueSpan(SpanRecord.newBuilder().setTraceId("t1").setSpanId("s1").build());
        assertDoesNotThrow(flusher::flushSpans);

        flusher.enqueueSpan(SpanRecord.newBuilder().setTraceId("t2").setSpanId("s2").build());
        assertDoesNotThrow(flusher::flushSpans);

        verify(spanWriter, times(2)).writeBatch(anyList());
    }
}
