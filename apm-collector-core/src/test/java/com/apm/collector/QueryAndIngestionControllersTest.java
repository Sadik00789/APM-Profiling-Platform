package com.apm.collector;

import com.apm.collector.config.DisruptorConfig;
import com.apm.collector.engine.anomaly.SlidingWindowLatencyTracker;
import com.apm.collector.engine.sampling.TailBasedSamplingFilter;
import com.apm.collector.engine.tracing.CriticalPathCalculator;
import com.apm.collector.engine.tracing.TraceDagReconstructor;
import com.apm.collector.ingestion.otlp.OtlpHttpTraceHandler;
import com.apm.collector.ingestion.otlp.OtlpSpanDecoder;
import com.apm.collector.ingestion.profiling.FoldedStackParser;
import com.apm.collector.ingestion.profiling.PyroscopePushHandler;
import com.apm.collector.query.ProfileQueryController;
import com.apm.collector.query.ServiceMapController;
import com.apm.collector.query.TraceQueryController;
import com.apm.collector.storage.ClickHouseBatchFlusher;
import com.apm.contracts.profile.v1.ProfileSample;
import com.apm.contracts.profile.v1.ProfileType;
import com.apm.contracts.trace.v1.BatchSpanRequest;
import com.apm.contracts.trace.v1.SpanRecord;
import com.apm.contracts.trace.v1.StatusCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lmax.disruptor.RingBuffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Mono;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class QueryAndIngestionControllersTest {

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("OtlpHttpTraceHandler should ingest JSON and Protobuf batches through Disruptor and update latency tracker")
    void testOtlpTraceHandlerIngestion() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ReactiveRedisTemplate<String, String> redis = mock(ReactiveRedisTemplate.class);
        when(redis.convertAndSend(anyString(), anyString())).thenReturn(Mono.just(1L));

        ClickHouseBatchFlusher flusher = mock(ClickHouseBatchFlusher.class);
        TailBasedSamplingFilter filter = new TailBasedSamplingFilter(flusher, 250.0, 1.0, 5_000L);
        SlidingWindowLatencyTracker tracker = new SlidingWindowLatencyTracker(250.0, 500.0, redis, mapper);

        DisruptorConfig disruptorConfig = new DisruptorConfig();
        java.lang.reflect.Field f = DisruptorConfig.class.getDeclaredField("ringBufferSize");
        f.setAccessible(true);
        f.setInt(disruptorConfig, 1024);
        RingBuffer<DisruptorConfig.SpanEvent> ringBuffer = disruptorConfig.spanRingBuffer(filter);

        try {
            OtlpHttpTraceHandler handler = new OtlpHttpTraceHandler(
                    new OtlpSpanDecoder(mapper), filter, tracker, redis, mapper, ringBuffer
            );

            // 1. Empty payload
            ResponseEntity<Map<String, Object>> emptyResp = handler.ingestTraces(new byte[0], "application/json").block();
            assertNotNull(emptyResp);
            assertEquals("empty_payload", emptyResp.getBody().get("status"));

            // 2. Protobuf payload with error span
            BatchSpanRequest batch = BatchSpanRequest.newBuilder()
                    .addSpans(SpanRecord.newBuilder()
                            .setTraceId("trace-proto")
                            .setSpanId("span-1")
                            .setServiceName("payment-service")
                            .setOperationName("POST /charge")
                            .setStartTimeUnixNano(1_700_000_000_000_000_000L)
                            .setDurationNano(120_000_000L)
                            .setStatusCode(StatusCode.STATUS_CODE_ERROR)
                            .build())
                    .build();

            ResponseEntity<Map<String, Object>> protoResp = handler.ingestTraces(batch.toByteArray(), "application/x-protobuf").block();
            assertNotNull(protoResp);
            assertEquals(1, protoResp.getBody().get("accepted"));

            // 3. JSON payload
            String json = """
                {"spans":[{"traceId":"trace-json","spanId":"span-2","serviceName":"payment-service","operationName":"POST /charge","durationNano":30000000,"statusCode":"OK"}]}
                """;
            ResponseEntity<Map<String, Object>> jsonResp = handler.ingestTraces(json.getBytes(StandardCharsets.UTF_8), "application/json").block();
            assertNotNull(jsonResp);
            assertEquals(1, jsonResp.getBody().get("accepted"));

            // Allow Disruptor consumer thread a moment to forward events to filter, then flush
            Thread.sleep(50L);
            filter.flushAllRemaining();
            verify(flusher, times(2)).enqueueSpan(any());

            assertEquals(2L, tracker.getServicePercentiles("payment-service").getTotalRequests());
            assertEquals(1L, tracker.getServicePercentiles("payment-service").getErrorCount());
        } finally {
            disruptorConfig.shutdown();
        }
    }

    @Test
    @DisplayName("PyroscopePushHandler should strip sample count from stack trace and parse profile type")
    void testPyroscopePushHandler() {
        ClickHouseBatchFlusher flusher = mock(ClickHouseBatchFlusher.class);
        PyroscopePushHandler handler = new PyroscopePushHandler(new FoldedStackParser(), flusher);

        String body = "java.lang.Thread.run;com.apm.OrderService.process 42\n";
        ResponseEntity<Map<String, Object>> resp = handler
                .ingestFoldedProfile(body, "order-service.alloc{env=prod}", null, null, "bytes", 100)
                .block();

        assertNotNull(resp);
        assertEquals("success", resp.getBody().get("status"));
        assertEquals("order-service", resp.getBody().get("service"));
        assertEquals(ProfileType.PROFILE_TYPE_ALLOC_SPACE.name(), resp.getBody().get("profile_type"));

        ArgumentCaptor<ProfileSample> sampleCaptor = ArgumentCaptor.forClass(ProfileSample.class);
        ArgumentCaptor<String> stackCaptor = ArgumentCaptor.forClass(String.class);
        verify(flusher).enqueueProfileSample(sampleCaptor.capture(), stackCaptor.capture());

        assertEquals(42L, sampleCaptor.getValue().getSampleCount());
        assertEquals("java.lang.Thread.run;com.apm.OrderService.process", stackCaptor.getValue());
    }

    @Test
    @DisplayName("ProfileQueryController should query both short/enum profile types and return real diff tree")
    void testProfileQueryControllerDiffAndFiltering() throws Exception {
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rsBase = mock(ResultSet.class);
        ResultSet rsComp = mock(ResultSet.class);

        when(ds.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rsBase, rsComp);

        when(rsBase.next()).thenReturn(true, false);
        when(rsBase.getString("stack_trace")).thenReturn("main;oldMethod");
        when(rsBase.getLong("total_samples")).thenReturn(100L);

        when(rsComp.next()).thenReturn(true, true, false);
        when(rsComp.getString("stack_trace")).thenReturn("main;oldMethod", "main;hotMethod");
        when(rsComp.getLong("total_samples")).thenReturn(50L, 150L);

        ProfileQueryController controller = new ProfileQueryController(ds);
        ResponseEntity<Map<String, Object>> diffResp = controller
                .getDiffFlameGraph("order-service", "ALLOC_SPACE", 100L, 200L, 300L, 400L)
                .block();

        assertNotNull(diffResp);
        assertEquals(100L, diffResp.getBody().get("baselineTotal"));
        assertEquals(200L, diffResp.getBody().get("comparisonTotal"));
        assertEquals(100.0, diffResp.getBody().get("overallChangePercent"));

        verify(ps, atLeastOnce()).setString(2, "PROFILE_TYPE_ALLOC_SPACE");
        verify(ps, atLeastOnce()).setString(3, "ALLOC_SPACE");

        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) diffResp.getBody().get("root");
        assertEquals(100L, root.get("diffValue"));
    }

    @Test
    @DisplayName("TraceQueryController should clamp pagination and reconstruct DAG from mocked ClickHouse result")
    void testTraceQueryController() throws Exception {
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);

        when(ds.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);

        Timestamp ts = new Timestamp(1_700_000_000_000L);
        when(rs.next()).thenReturn(true, false);
        when(rs.getTimestamp("start_time")).thenReturn(ts);
        when(rs.getString("trace_id")).thenReturn("trace-abc");
        when(rs.getString("span_id")).thenReturn("span-root");
        when(rs.getString("parent_span_id")).thenReturn("");
        when(rs.getString("service_name")).thenReturn("api-gateway");
        when(rs.getString("operation_name")).thenReturn("GET /health");
        when(rs.getLong("duration_nano")).thenReturn(25_000_000L);
        when(rs.getString("status_code")).thenReturn("STATUS_CODE_OK");
        when(rs.getObject("attributes")).thenReturn(Map.of("http.status_code", "200"));

        TraceQueryController controller = new TraceQueryController(ds, new TraceDagReconstructor(new CriticalPathCalculator()));

        ResponseEntity<Map<String, Object>> searchResp = controller
                .searchSpans("api-gateway", "health", "OK", null, null, null, null, 9999, -5)
                .block();
        assertNotNull(searchResp);
        assertEquals(500, searchResp.getBody().get("limit"));
        assertEquals(0, searchResp.getBody().get("offset"));
        assertEquals(1, searchResp.getBody().get("count"));

        when(rs.next()).thenReturn(true, false);
        ResponseEntity<Map<String, Object>> treeResp = controller.getTraceTree("trace-abc").block();
        assertNotNull(treeResp);
        assertEquals(true, treeResp.getBody().get("found"));
        assertEquals(1, treeResp.getBody().get("totalSpans"));
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("ServiceMapController should compute live health, topology, and latency histograms from tracker")
    void testServiceMapControllerLiveMetrics() throws Exception {
        DataSource ds = mock(DataSource.class);
        when(ds.getConnection()).thenThrow(new RuntimeException("offline"));

        ReactiveRedisTemplate<String, String> redis = mock(ReactiveRedisTemplate.class);
        when(redis.convertAndSend(anyString(), anyString())).thenReturn(Mono.just(1L));
        SlidingWindowLatencyTracker tracker = new SlidingWindowLatencyTracker(250.0, 500.0, redis, new ObjectMapper());

        // 10 requests with 2 errors -> 20% error rate -> CRITICAL
        for (int i = 0; i < 10; i++) {
            tracker.recordSpan("payment-service", "POST /charge", 150.0 + i * 10, i < 2);
        }

        ServiceMapController controller = new ServiceMapController(ds, tracker);

        ResponseEntity<Map<String, Object>> health = controller.getGlobalHealth().block();
        assertNotNull(health);
        assertEquals(20.0, health.getBody().get("globalErrorRatePercent"));
        List<Map<String, Object>> services = (List<Map<String, Object>>) health.getBody().get("services");
        assertEquals("CRITICAL", services.get(0).get("status"));

        ResponseEntity<Map<String, Object>> topology = controller.getServiceTopology().block();
        assertNotNull(topology);
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) topology.getBody().get("nodes");
        Map<String, Object> paymentNode = nodes.stream()
                .filter(n -> "payment-service".equals(n.get("id")))
                .findFirst().orElseThrow();
        assertEquals("critical", paymentNode.get("status"));
        assertEquals(20.0, paymentNode.get("errorRatePercent"));

        ResponseEntity<Map<String, Object>> histogram = controller.getLatencyHistogram("payment-service").block();
        assertNotNull(histogram);
        assertEquals(10L, histogram.getBody().get("totalCount"));
    }
}
