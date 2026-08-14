package com.apm.collector.config;

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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
    public RingBuffer<SpanEvent> spanRingBuffer() {
        SpanEventFactory factory = new SpanEventFactory();
        this.disruptor = new Disruptor<>(
                factory,
                ringBufferSize,
                DaemonThreadFactory.INSTANCE,
                ProducerType.MULTI,
                new BlockingWaitStrategy()
        );
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
