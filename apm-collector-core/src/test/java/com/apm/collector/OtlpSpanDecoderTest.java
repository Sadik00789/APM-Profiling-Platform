package com.apm.collector;

import com.apm.collector.ingestion.otlp.OtlpSpanDecoder;
import com.apm.contracts.trace.v1.SpanKind;
import com.apm.contracts.trace.v1.SpanRecord;
import com.apm.contracts.trace.v1.StatusCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OtlpSpanDecoderTest {

    private final OtlpSpanDecoder decoder = new OtlpSpanDecoder(new ObjectMapper());

    @Test
    @DisplayName("Should decode standard OTLP resourceSpans payload with attributes, status, and span kind")
    void testDecodeStandardOtlpResourceSpans() {
        String json = """
            {
              "resourceSpans": [
                {
                  "resource": {
                    "attributes": [
                      { "key": "service.name", "value": { "stringValue": "payment-service" } },
                      { "key": "service.version", "value": { "stringValue": "2.1.0" } }
                    ]
                  },
                  "scopeSpans": [
                    {
                      "spans": [
                        {
                          "traceId": "abc123",
                          "spanId": "span01",
                          "parentSpanId": "root00",
                          "name": "POST /charge",
                          "kind": 3,
                          "startTimeUnixNano": "1700000000000000000",
                          "endTimeUnixNano": "1700000000050000000",
                          "attributes": [
                            { "key": "http.status_code", "value": { "intValue": 500 } },
                            { "key": "retryable", "value": { "boolValue": true } }
                          ],
                          "status": { "code": 2, "message": "gateway timeout" }
                        }
                      ]
                    }
                  ]
                }
              ]
            }
            """;

        List<SpanRecord> spans = decoder.decodeJson(json);
        assertEquals(1, spans.size());
        SpanRecord span = spans.get(0);

        assertEquals("payment-service", span.getServiceName());
        assertEquals("POST /charge", span.getOperationName());
        assertEquals(SpanKind.SPAN_KIND_CLIENT, span.getSpanKind());
        assertEquals(50_000_000L, span.getDurationNano());
        assertEquals(StatusCode.STATUS_CODE_ERROR, span.getStatusCode());
        assertEquals("gateway timeout", span.getStatusMessage());
        assertEquals("2.1.0", span.getAttributesOrDefault("service.version", ""));
        assertEquals("500", span.getAttributesOrDefault("http.status_code", ""));
        assertEquals("true", span.getAttributesOrDefault("retryable", ""));
    }

    @Test
    @DisplayName("Should decode direct spans array and derive duration when end time is present without duration")
    void testDecodeDirectSpansDerivedDuration() {
        String json = """
            {
              "spans": [
                {
                  "traceId": "t-1",
                  "spanId": "s-1",
                  "serviceName": "order-service",
                  "operationName": "createOrder",
                  "spanKind": "PRODUCER",
                  "startTimeUnixNano": 1000000000,
                  "endTimeUnixNano": 1045000000,
                  "statusCode": "OK"
                }
              ]
            }
            """;

        List<SpanRecord> spans = decoder.decodeJson(json);
        assertEquals(1, spans.size());
        SpanRecord span = spans.get(0);
        assertEquals(45_000_000L, span.getDurationNano());
        assertEquals(SpanKind.SPAN_KIND_PRODUCER, span.getSpanKind());
        assertEquals(StatusCode.STATUS_CODE_OK, span.getStatusCode());
    }

    @Test
    @DisplayName("Should return empty list for null, blank, or malformed JSON")
    void testEmptyAndMalformed() {
        assertTrue(decoder.decodeJson(null).isEmpty());
        assertTrue(decoder.decodeJson("   ").isEmpty());
        assertTrue(decoder.decodeJson("{not-json").isEmpty());
    }
}
