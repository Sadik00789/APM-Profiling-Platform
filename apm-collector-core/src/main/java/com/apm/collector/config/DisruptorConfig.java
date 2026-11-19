package com.apm.collector.config;

import com.apm.collector.engine.sampling.TailBasedSamplingFilter;
import com.apm.contracts.trace.v1.SpanRecord;
import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.EventFactory;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.disruptor.util.DaemonThreadFactory;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class DisruptorConfig {

    @Value("${apm.disruptor.ring-buffer-size:65536}")
    private int ringBufferSize;

    private Disruptor<SpanEvent> disruptor;

    @Getter
    @Setter
    public static class SpanEvent {
        private SpanRecord span;

        public void clear() {
            this.span = null;
        }
    }

    public static class SpanEventFactory implements EventFactory<SpanEvent> {
        @Override
        public SpanEvent newInstance() {
            return new SpanEvent();
        }
    }

    @Bean
    public RingBuffer<SpanEvent> spanRingBuffer(TailBasedSamplingFilter samplingFilter) {
        SpanEventFactory factory = new SpanEventFactory();
        this.disruptor = new Disruptor<>(
                factory,
                ringBufferSize,
                DaemonThreadFactory.INSTANCE,
                ProducerType.MULTI,
                new BlockingWaitStrategy()
        );
        disruptor.handleEventsWith((event, sequence, endOfBatch) -> {
            try {
                SpanRecord span = event.getSpan();
                if (span != null) {
                    samplingFilter.acceptSpan(span);
                }
            } catch (Exception e) {
                log.error("Disruptor span processing failed at seq {}: {}", sequence, e.getMessage());
            } finally {
                event.clear();
            }
        });
        disruptor.start();
        return disruptor.getRingBuffer();
    }

    @PreDestroy
    public void shutdown() {
        if (disruptor != null) {
            disruptor.shutdown();
        }
    }
}
