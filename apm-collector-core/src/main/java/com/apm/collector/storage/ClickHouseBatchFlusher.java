package com.apm.collector.storage;

import com.apm.contracts.profile.v1.ProfileSample;
import com.apm.contracts.trace.v1.SpanRecord;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

@Slf4j
@Component
public class ClickHouseBatchFlusher {

    private final SpanWriter spanWriter;
    private final ProfileSampleWriter profileSampleWriter;

    private final int maxBatchSize;
    private final BlockingQueue<SpanRecord> spanQueue;
    private final BlockingQueue<ProfileSampleWriter.ProfileSampleEntry> profileQueue;
    private final ExecutorService virtualThreadExecutor;

    public ClickHouseBatchFlusher(
            SpanWriter spanWriter,
            ProfileSampleWriter profileSampleWriter,
            @Value("${apm.storage.batch.buffer-capacity:50000}") int bufferCapacity,
            @Value("${apm.storage.batch.max-batch-size:5000}") int maxBatchSize) {
        this.spanWriter = spanWriter;
        this.profileSampleWriter = profileSampleWriter;
        this.maxBatchSize = maxBatchSize;
        this.spanQueue = new LinkedBlockingQueue<>(bufferCapacity);
        this.profileQueue = new LinkedBlockingQueue<>(bufferCapacity);
        this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    public void enqueueSpan(SpanRecord span) {
        if (span == null) return;
        boolean accepted = spanQueue.offer(span);
        if (!accepted) {
            log.warn("Span queue is full (capacity {}), dropping span {}", spanQueue.size(), span.getSpanId());
        }
        if (spanQueue.size() >= maxBatchSize) {
            virtualThreadExecutor.submit(this::flushSpans);
        }
    }

    public void enqueueProfileSample(ProfileSample sample, String rawLine) {
        if (sample == null) return;
        boolean accepted = profileQueue.offer(new ProfileSampleWriter.ProfileSampleEntry(sample, rawLine));
        if (!accepted) {
            log.warn("Profile queue is full, dropping profile sample for {}", sample.getServiceName());
        }
        if (profileQueue.size() >= maxBatchSize) {
            virtualThreadExecutor.submit(this::flushProfiles);
        }
    }

    @Scheduled(fixedDelayString = "${apm.storage.batch.flush-interval-ms:500}")
    public void scheduledFlush() {
        if (!spanQueue.isEmpty()) {
            virtualThreadExecutor.submit(this::flushSpans);
        }
        if (!profileQueue.isEmpty()) {
            virtualThreadExecutor.submit(this::flushProfiles);
        }
    }

    private synchronized void flushSpans() {
        if (spanQueue.isEmpty()) return;

        List<SpanRecord> batch = new ArrayList<>(Math.min(spanQueue.size(), maxBatchSize));
        spanQueue.drainTo(batch, maxBatchSize);

        if (!batch.isEmpty()) {
            try {
                spanWriter.writeBatch(batch);
            } catch (Exception e) {
                log.error("Failed to flush span batch of size {}: {}", batch.size(), e.getMessage());
            }
        }
    }

    private synchronized void flushProfiles() {
        if (profileQueue.isEmpty()) return;

        List<ProfileSampleWriter.ProfileSampleEntry> batch = new ArrayList<>(Math.min(profileQueue.size(), maxBatchSize));
        profileQueue.drainTo(batch, maxBatchSize);

        if (!batch.isEmpty()) {
            try {
                profileSampleWriter.writeBatch(batch);
            } catch (Exception e) {
                log.error("Failed to flush profile batch of size {}: {}", batch.size(), e.getMessage());
            }
        }
    }

    @PreDestroy
    public void onShutdown() {
        log.info("Flushing remaining in-memory batches before shutdown...");
        flushSpans();
        flushProfiles();
        virtualThreadExecutor.shutdown();
        try {
            if (!virtualThreadExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                virtualThreadExecutor.shutdownNow();
            }
        } catch (InterruptedException ignored) {
            virtualThreadExecutor.shutdownNow();
        }
    }
}
