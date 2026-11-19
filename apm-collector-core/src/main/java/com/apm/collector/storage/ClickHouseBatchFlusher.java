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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Component
public class ClickHouseBatchFlusher {

    private final SpanWriter spanWriter;
    private final ProfileSampleWriter profileSampleWriter;

    private final int maxBatchSize;
    private final BlockingQueue<SpanRecord> spanQueue;
    private final BlockingQueue<ProfileSampleWriter.ProfileSampleEntry> profileQueue;
    private final ExecutorService virtualThreadExecutor;
    private final ReentrantLock spanFlushLock = new ReentrantLock();
    private final ReentrantLock profileFlushLock = new ReentrantLock();
    private final AtomicBoolean spanFlushScheduled = new AtomicBoolean(false);
    private final AtomicBoolean profileFlushScheduled = new AtomicBoolean(false);

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
            triggerSpanFlushAsync();
        }
    }

    public void enqueueProfileSample(ProfileSample sample, String rawLine) {
        if (sample == null) return;
        boolean accepted = profileQueue.offer(new ProfileSampleWriter.ProfileSampleEntry(sample, rawLine));
        if (!accepted) {
            log.warn("Profile queue is full, dropping profile sample for {}", sample.getServiceName());
        }
        if (profileQueue.size() >= maxBatchSize) {
            triggerProfileFlushAsync();
        }
    }

    @Scheduled(fixedDelayString = "${apm.storage.batch.flush-interval-ms:500}")
    public void scheduledFlush() {
        if (!spanQueue.isEmpty()) {
            triggerSpanFlushAsync();
        }
        if (!profileQueue.isEmpty()) {
            triggerProfileFlushAsync();
        }
    }

    private void triggerSpanFlushAsync() {
        if (spanFlushScheduled.compareAndSet(false, true)) {
            virtualThreadExecutor.submit(() -> {
                spanFlushScheduled.set(false);
                flushSpans();
            });
        }
    }

    private void triggerProfileFlushAsync() {
        if (profileFlushScheduled.compareAndSet(false, true)) {
            virtualThreadExecutor.submit(() -> {
                profileFlushScheduled.set(false);
                flushProfiles();
            });
        }
    }

    public void flushSpans() {
        spanFlushLock.lock();
        try {
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
        } finally {
            spanFlushLock.unlock();
        }
    }

    public void flushProfiles() {
        profileFlushLock.lock();
        try {
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
        } finally {
            profileFlushLock.unlock();
        }
    }

    @PreDestroy
    public void onShutdown() {
        log.info("Flushing remaining in-memory batches before shutdown...");
        while (!spanQueue.isEmpty()) {
            flushSpans();
        }
        while (!profileQueue.isEmpty()) {
            flushProfiles();
        }
        virtualThreadExecutor.shutdown();
        try {
            if (!virtualThreadExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                virtualThreadExecutor.shutdownNow();
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            virtualThreadExecutor.shutdownNow();
        }
    }
}
