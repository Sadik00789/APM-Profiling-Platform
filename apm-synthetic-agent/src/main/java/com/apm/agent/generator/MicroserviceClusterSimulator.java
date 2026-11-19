package com.apm.agent.generator;

import com.apm.agent.emitter.OtlpSpanEmitter;
import com.apm.contracts.trace.v1.SpanKind;
import com.apm.contracts.trace.v1.SpanRecord;
import com.apm.contracts.trace.v1.StatusCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class MicroserviceClusterSimulator {

    private final TraceContextPropagator contextPropagator;
    private final OtlpSpanEmitter spanEmitter;
    private final ChaosStateService chaosStateService;

    @Value("${apm.agent.traffic.error-rate-percent:3.5}")
    private double defaultErrorRatePercent;

    @Value("${apm.agent.traffic.chaos-mode-enabled:true}")
    private boolean chaosModeEnabled;

    private final Random random = new Random();

    @Scheduled(fixedRateString = "${apm.agent.traffic.simulation-rate-ms:150}")
    public void simulateTransaction() {
        try {
            spanEmitter.emitSpans(generateTraceSpans());
        } catch (Exception e) {
            log.error("Simulation error: {}", e.getMessage(), e);
        }
    }

    public List<SpanRecord> generateTraceSpans() {
        List<SpanRecord> traceSpans = new ArrayList<>();
        long gatewayStart = System.currentTimeMillis() * 1_000_000L;

        ChaosStateService.ChaosStatus chaos = chaosStateService.getStatus();
        boolean isDbLatencyScenario = chaos.active() && chaos.scenario() == ChaosStateService.ChaosScenario.DB_LATENCY;
        boolean isErrorStormScenario = chaos.active() && chaos.scenario() == ChaosStateService.ChaosScenario.ERROR_STORM;
        boolean isCpuSpikeScenario = chaos.active() && chaos.scenario() == ChaosStateService.ChaosScenario.CPU_SPIKE;
        double intensityScale = chaos.active() ? Math.max(0.25, chaos.intensity()) : 1.0;

        double currentErrorRate = isErrorStormScenario
                ? Math.min(100.0, Math.max(defaultErrorRatePercent, 85.0 * chaos.intensity()))
                : defaultErrorRatePercent;
        boolean isError = (random.nextDouble() * 100.0) < currentErrorRate;

        TraceContextPropagator.TraceContext rootCtx = contextPropagator.createRootContext();
        long orderStart = gatewayStart + 2_000_000L;

        // 3. Inventory Service & Inventory DB spans (bottom-up timing so parent encloses child)
        TraceContextPropagator.TraceContext orderCtx = contextPropagator.createChildContext(rootCtx);
        TraceContextPropagator.TraceContext invCtx = contextPropagator.createChildContext(orderCtx);
        long invStart = orderStart + 3_000_000L;
        long dbDurationMs = isDbLatencyScenario
                ? Math.round((350 + random.nextInt(900)) * intensityScale)
                : (4 + random.nextInt(4));
        long invDbStart = invStart + 1_000_000L;
        long invDbDuration = dbDurationMs * 1_000_000L;

        TraceContextPropagator.TraceContext invDbCtx = contextPropagator.createChildContext(invCtx);
        SpanRecord invDbSpan = createSpan(
                invDbCtx,
                "inventory-service",
                "SQL UPDATE inventory SET stock = stock - 1",
                invDbStart,
                invDbDuration,
                StatusCode.STATUS_CODE_OK,
                Map.of(
                        "db.system", "postgresql",
                        "db.statement", "UPDATE inventory SET stock = stock - 1 WHERE sku = ?",
                        "db.latency_injected", isDbLatencyScenario ? "true" : "false"
                )
        );

        long invDuration = (invDbStart - invStart) + invDbDuration + (8 + random.nextInt(6)) * 1_000_000L;
        SpanRecord invSpan = createSpan(
                invCtx,
                "inventory-service",
                "POST /inventory/reserve",
                invStart,
                invDuration,
                StatusCode.STATUS_CODE_OK,
                Map.of("sku.id", "sku-" + random.nextInt(500), "quantity", "1")
        );

        // 4. Payment Service & Stripe External Gateway Call (bottom-up timing)
        TraceContextPropagator.TraceContext payCtx = contextPropagator.createChildContext(orderCtx);
        long payStart = invStart + invDuration + 2_000_000L;
        long stripeStart = payStart + 3_000_000L;
        long stripeDurationMs = isCpuSpikeScenario
                ? Math.round((320 + random.nextInt(80)) * intensityScale)
                : (55 + random.nextInt(30));
        long stripeDuration = stripeDurationMs * 1_000_000L;

        TraceContextPropagator.TraceContext stripeCtx = contextPropagator.createChildContext(payCtx);
        SpanRecord stripeSpan = createSpan(
                stripeCtx,
                "payment-service",
                "HTTP POST https://api.stripe.com/v1/charges",
                stripeStart,
                stripeDuration,
                isError ? StatusCode.STATUS_CODE_ERROR : StatusCode.STATUS_CODE_OK,
                Map.of("http.url", "https://api.stripe.com/v1/charges", "peer.service", "stripe")
        );

        long payDuration = (stripeStart - payStart) + stripeDuration + 5_000_000L;
        SpanRecord paySpan = createSpan(
                payCtx,
                "payment-service",
                "POST /payments/charge",
                payStart,
                payDuration,
                isError ? StatusCode.STATUS_CODE_ERROR : StatusCode.STATUS_CODE_OK,
                Map.of(
                        "payment.provider", "stripe",
                        "payment.method", "credit_card",
                        "http.status_code", isError ? "500" : "200"
                )
        );

        // 5. Notification Service Span
        TraceContextPropagator.TraceContext notifCtx = contextPropagator.createChildContext(orderCtx);
        long notifStart = payStart + payDuration + 1_000_000L;
        long notifDuration = (15 + random.nextInt(8)) * 1_000_000L;
        SpanRecord notifSpan = createSpan(
                notifCtx,
                "notification-service",
                "POST /notifications/email",
                notifStart,
                notifDuration,
                StatusCode.STATUS_CODE_OK,
                Map.of("notification.channel", "email", "template", "order_confirmation")
        );

        // 2. Order Service Span encloses inventory, payment, and notification
        long orderEnd = notifStart + notifDuration + 2_000_000L;
        long orderDuration = orderEnd - orderStart;
        SpanRecord orderSpan = createSpan(
                orderCtx,
                "order-service",
                "POST /orders/create",
                orderStart,
                orderDuration,
                isError ? StatusCode.STATUS_CODE_ERROR : StatusCode.STATUS_CODE_OK,
                Map.of("user.id", "usr-" + random.nextInt(10000), "order.amount", "$" + (10 + random.nextInt(200)))
        );

        // 1. Gateway Root Span strictly encloses order span
        long gatewayEnd = orderEnd + 3_000_000L;
        long gatewayDuration = gatewayEnd - gatewayStart;
        SpanRecord gatewaySpan = createSpan(
                rootCtx,
                "api-gateway",
                "POST /api/v1/checkout",
                gatewayStart,
                gatewayDuration,
                isError ? StatusCode.STATUS_CODE_ERROR : StatusCode.STATUS_CODE_OK,
                Map.of("http.method", "POST", "http.url", "/api/v1/checkout", "http.status_code", isError ? "500" : "200")
        );

        traceSpans.add(gatewaySpan);
        traceSpans.add(orderSpan);
        traceSpans.add(invSpan);
        traceSpans.add(invDbSpan);
        traceSpans.add(paySpan);
        traceSpans.add(stripeSpan);
        traceSpans.add(notifSpan);

        return traceSpans;
    }

    private SpanRecord createSpan(
            TraceContextPropagator.TraceContext ctx,
            String serviceName,
            String operationName,
            long startNano,
            long durationNano,
            StatusCode status,
            Map<String, String> attributes) {

        return SpanRecord.newBuilder()
                .setTraceId(ctx.traceId())
                .setSpanId(ctx.spanId())
                .setParentSpanId(ctx.parentSpanId())
                .setServiceName(serviceName)
                .setOperationName(operationName)
                .setStartTimeUnixNano(startNano)
                .setEndTimeUnixNano(startNano + durationNano)
                .setDurationNano(durationNano)
                .setStatusCode(status)
                .setStatusMessage(status == StatusCode.STATUS_CODE_ERROR ? "Internal Server Error" : "OK")
                .setSpanKind(SpanKind.SPAN_KIND_SERVER)
                .putAllAttributes(attributes)
                .build();
    }
}
