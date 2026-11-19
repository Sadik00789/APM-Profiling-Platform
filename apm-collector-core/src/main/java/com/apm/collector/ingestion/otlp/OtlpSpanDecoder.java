package com.apm.collector.ingestion.otlp;

import com.apm.contracts.trace.v1.SpanKind;
import com.apm.contracts.trace.v1.SpanRecord;
import com.apm.contracts.trace.v1.StatusCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class OtlpSpanDecoder {

    private final ObjectMapper objectMapper;

    /**
     * Decodes OTLP /v1/traces JSON format (resourceSpans -> scopeSpans -> spans)
     * or unified APM batch JSON into a list of SpanRecord models.
     */
    public List<SpanRecord> decodeJson(String jsonPayload) {
        if (jsonPayload == null || jsonPayload.isBlank()) {
            return Collections.emptyList();
        }

        try {
            JsonNode root = objectMapper.readTree(jsonPayload);
            List<SpanRecord> records = new ArrayList<>();

            // 1. Direct "spans" array format
            if (root.has("spans") && root.get("spans").isArray()) {
                for (JsonNode spanNode : root.get("spans")) {
                    SpanRecord record = parseDirectSpanNode(spanNode);
                    if (record != null) {
                        records.add(record);
                    }
                }
                return records;
            }

            // 2. Standard OTLP resourceSpans format
            if (root.has("resourceSpans") && root.get("resourceSpans").isArray()) {
                for (JsonNode resSpan : root.get("resourceSpans")) {
                    String serviceName = extractServiceName(resSpan);
                    Map<String, String> resourceAttrs = extractAttributes(resSpan.path("resource").path("attributes"));

                    JsonNode scopeSpans = resSpan.path("scopeSpans");
                    if (scopeSpans.isArray()) {
                        for (JsonNode scopeSpan : scopeSpans) {
                            JsonNode spans = scopeSpan.path("spans");
                            if (spans.isArray()) {
                                for (JsonNode spanNode : spans) {
                                    SpanRecord record = parseOtlpSpanNode(spanNode, serviceName, resourceAttrs);
                                    if (record != null) {
                                        records.add(record);
                                    }
                                }
                            }
                        }
                    }
                }
            }

            return records;
        } catch (Exception e) {
            log.error("Failed to decode OTLP trace JSON payload: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    private SpanRecord parseDirectSpanNode(JsonNode node) {
        String traceId = node.path("trace_id").asText(node.path("traceId").asText(UUID.randomUUID().toString().replace("-", "")));
        String spanId = node.path("span_id").asText(node.path("spanId").asText(UUID.randomUUID().toString().substring(0, 16)));
        String parentSpanId = node.path("parent_span_id").asText(node.path("parentSpanId").asText(""));
        String serviceName = node.path("service_name").asText(node.path("serviceName").asText("unknown-service"));
        String operationName = node.path("operation_name").asText(node.path("operationName").asText("operation"));

        long startNano = node.hasNonNull("start_time_unix_nano")
                ? parseNano(node.get("start_time_unix_nano"))
                : (node.hasNonNull("startTimeUnixNano") ? parseNano(node.get("startTimeUnixNano")) : System.currentTimeMillis() * 1_000_000L);

        boolean hasDuration = node.hasNonNull("duration_nano") || node.hasNonNull("durationNano");
        long durationNano = node.hasNonNull("duration_nano")
                ? parseNano(node.get("duration_nano"))
                : (node.hasNonNull("durationNano") ? parseNano(node.get("durationNano")) : 0L);

        boolean hasEnd = node.hasNonNull("end_time_unix_nano") || node.hasNonNull("endTimeUnixNano");
        long endNano = node.hasNonNull("end_time_unix_nano")
                ? parseNano(node.get("end_time_unix_nano"))
                : (node.hasNonNull("endTimeUnixNano") ? parseNano(node.get("endTimeUnixNano")) : 0L);

        if (!hasDuration) {
            if (hasEnd && endNano >= startNano) {
                durationNano = endNano - startNano;
            } else {
                durationNano = 10_000_000L;
            }
        } else if (durationNano == 0 && hasEnd && endNano >= startNano) {
            durationNano = endNano - startNano;
        }
        if (!hasEnd) {
            endNano = startNano + durationNano;
        }

        String statusStr = node.path("status_code").asText(node.path("statusCode").asText("STATUS_CODE_OK")).toUpperCase();
        StatusCode statusCode = parseStatusCode(statusStr);
        String statusMsg = node.path("status_message").asText(node.path("statusMessage").asText(""));
        JsonNode kindNode = node.has("span_kind")
                ? node.get("span_kind")
                : (node.has("spanKind") ? node.get("spanKind") : node.path("kind"));
        SpanKind spanKind = parseSpanKind(kindNode, SpanKind.SPAN_KIND_SERVER);

        Map<String, String> attributes = new HashMap<>();
        JsonNode attrsNode = node.has("attributes") ? node.get("attributes") : node.get("tags");
        if (attrsNode != null && attrsNode.isObject()) {
            attrsNode.fields().forEachRemaining(entry -> attributes.put(entry.getKey(), entry.getValue().asText()));
        }

        return SpanRecord.newBuilder()
                .setTraceId(traceId)
                .setSpanId(spanId)
                .setParentSpanId(parentSpanId)
                .setServiceName(serviceName)
                .setOperationName(operationName)
                .setStartTimeUnixNano(startNano)
                .setEndTimeUnixNano(endNano)
                .setDurationNano(durationNano)
                .setStatusCode(statusCode)
                .setStatusMessage(statusMsg)
                .setSpanKind(spanKind)
                .putAllAttributes(attributes)
                .build();
    }

    private SpanRecord parseOtlpSpanNode(JsonNode node, String serviceName, Map<String, String> resourceAttrs) {
        String traceId = node.path("traceId").asText("");
        String spanId = node.path("spanId").asText("");
        String parentSpanId = node.path("parentSpanId").asText("");
        String operationName = node.path("name").asText("unknown-op");

        long startNano = parseNano(node.path("startTimeUnixNano"));
        long endNano = parseNano(node.path("endTimeUnixNano"));
        long durationNano = (endNano >= startNano && startNano > 0) ? (endNano - startNano) : 0L;

        JsonNode statusNode = node.path("status");
        JsonNode codeNode = statusNode.path("code");
        String statusCodeStr = codeNode.isIntegralNumber()
                ? String.valueOf(codeNode.asInt())
                : codeNode.asText("STATUS_CODE_OK");
        StatusCode statusCode = parseStatusCode(statusCodeStr);
        String statusMessage = statusNode.path("message").asText("");
        SpanKind spanKind = parseSpanKind(node.path("kind"), SpanKind.SPAN_KIND_INTERNAL);

        Map<String, String> attributes = new HashMap<>(resourceAttrs);
        attributes.putAll(extractAttributes(node.path("attributes")));

        return SpanRecord.newBuilder()
                .setTraceId(traceId)
                .setSpanId(spanId)
                .setParentSpanId(parentSpanId)
                .setServiceName(serviceName)
                .setOperationName(operationName)
                .setStartTimeUnixNano(startNano)
                .setEndTimeUnixNano(endNano)
                .setDurationNano(durationNano)
                .setStatusCode(statusCode)
                .setStatusMessage(statusMessage)
                .setSpanKind(spanKind)
                .putAllAttributes(attributes)
                .build();
    }

    private String extractServiceName(JsonNode resSpan) {
        JsonNode attrs = resSpan.path("resource").path("attributes");
        if (attrs.isArray()) {
            for (JsonNode attr : attrs) {
                if ("service.name".equals(attr.path("key").asText())) {
                    return attr.path("value").path("stringValue").asText("unknown-service");
                }
            }
        }
        return "unknown-service";
    }

    private Map<String, String> extractAttributes(JsonNode attrsNode) {
        Map<String, String> map = new HashMap<>();
        if (attrsNode == null) return map;

        if (attrsNode.isArray()) {
            for (JsonNode item : attrsNode) {
                String key = item.path("key").asText("");
                if (!key.isEmpty()) {
                    JsonNode val = item.path("value");
                    if (val.has("stringValue")) map.put(key, val.path("stringValue").asText());
                    else if (val.has("intValue")) map.put(key, val.path("intValue").asText());
                    else if (val.has("boolValue")) map.put(key, val.path("boolValue").asText());
                    else if (val.has("doubleValue")) map.put(key, val.path("doubleValue").asText());
                    else map.put(key, val.asText());
                }
            }
        } else if (attrsNode.isObject()) {
            attrsNode.fields().forEachRemaining(entry -> map.put(entry.getKey(), entry.getValue().asText()));
        }
        return map;
    }

    private long parseNano(JsonNode node) {
        if (node.isIntegralNumber()) return node.asLong();
        try {
            String text = node.asText();
            return text.isEmpty() ? 0L : Long.parseLong(text);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private StatusCode parseStatusCode(String val) {
        if (val == null) return StatusCode.STATUS_CODE_OK;
        String upper = val.toUpperCase();
        if (upper.contains("ERROR") || "2".equals(val) || "STATUS_CODE_ERROR".equals(upper)) {
            return StatusCode.STATUS_CODE_ERROR;
        }
        if (upper.contains("UNSET") || "0".equals(val)) {
            return StatusCode.STATUS_CODE_UNSET;
        }
        return StatusCode.STATUS_CODE_OK;
    }

    private SpanKind parseSpanKind(JsonNode kindNode, SpanKind defaultKind) {
        if (kindNode == null || kindNode.isMissingNode() || kindNode.isNull()) {
            return defaultKind;
        }
        if (kindNode.isIntegralNumber()) {
            return switch (kindNode.asInt()) {
                case 0 -> SpanKind.SPAN_KIND_UNSPECIFIED;
                case 1 -> SpanKind.SPAN_KIND_INTERNAL;
                case 2 -> SpanKind.SPAN_KIND_SERVER;
                case 3 -> SpanKind.SPAN_KIND_CLIENT;
                case 4 -> SpanKind.SPAN_KIND_PRODUCER;
                case 5 -> SpanKind.SPAN_KIND_CONSUMER;
                default -> defaultKind;
            };
        }
        String text = kindNode.asText("").trim().toUpperCase();
        if (text.isEmpty()) return defaultKind;
        return switch (text) {
            case "1", "INTERNAL", "SPAN_KIND_INTERNAL" -> SpanKind.SPAN_KIND_INTERNAL;
            case "2", "SERVER", "SPAN_KIND_SERVER" -> SpanKind.SPAN_KIND_SERVER;
            case "3", "CLIENT", "SPAN_KIND_CLIENT" -> SpanKind.SPAN_KIND_CLIENT;
            case "4", "PRODUCER", "SPAN_KIND_PRODUCER" -> SpanKind.SPAN_KIND_PRODUCER;
            case "5", "CONSUMER", "SPAN_KIND_CONSUMER" -> SpanKind.SPAN_KIND_CONSUMER;
            case "0", "UNSPECIFIED", "SPAN_KIND_UNSPECIFIED" -> SpanKind.SPAN_KIND_UNSPECIFIED;
            default -> defaultKind;
        };
    }
}
