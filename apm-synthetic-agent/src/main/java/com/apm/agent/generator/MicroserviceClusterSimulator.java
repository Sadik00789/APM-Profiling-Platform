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
            List<SpanRecord> traceSpans = new ArrayList<>();
            long nowNano = System.currentTimeMillis() * 1_000_000L;

            ChaosStateService.ChaosStatus chaos = chaosStateService.getStatus();
            boolean isDbLatencyScenario = chaos.active() && chaos.scenario() == ChaosStateService.ChaosScenario.DB_LATENCY;
            boolean isErrorStormScenario = chaos.active() && chaos.scenario() == ChaosStateService.ChaosScenario.ERROR_STORM;
            boolean isCpuSpikeScenario = chaos.active() && chaos.scenario() == ChaosStateService.ChaosScenario.CPU_SPIKE;

            double currentErrorRate = isErrorStormScenario ? 85.0 : defaultErrorRatePercent;
            boolean isError = (random.nextDouble() * 100.0) < currentErrorRate;

            TraceContextPropagator.TraceContext rootCtx = contextPropagator.createRootContext();

            // 1. Gateway Span
            long baseGatewayDuration = (isCpuSpikeScenario ? 450 : isDbLatencyScenario ? 600 : 45 + random.nextInt(20)) * 1_000_000L;
            SpanRecord gatewaySpan = createSpan(
                    rootCtx,
                    "api-gateway",
                    "POST /api/v1/checkout",
                    nowNano,
                    baseGatewayDuration,
                    isError ? StatusCode.STATUS_CODE_ERROR : StatusCode.STATUS_CODE_OK,
                    Map.of("http.method", "POST", "http.url", "/api/v1/checkout", "http.status_code", isError ? "500" : "200")
            );
            traceSpans.add(gatewaySpan);

            // 2. Order Service Span
            TraceContextPropagator.TraceContext orderCtx = contextPropagator.createChildContext(rootCtx);
            long orderStart = nowNano + 2_000_000L;
            long orderDuration = baseGatewayDuration - 5_000_000L;
            SpanRecord orderSpan = createSpan(
                    orderCtx,
                    "order-service",
                    "POST /orders/create",
                    orderStart,
                    orderDuration,
                    isError ? StatusCode.STATUS_CODE_ERROR : StatusCode.STATUS_CODE_OK,
                    Map.of("user.id", "usr-" + random.nextInt(10000), "order.amount", "$" + (10 + random.nextInt(200)))
            );
            traceSpans.add(orderSpan);

            // 3. Inventory Service Span
            TraceContextPropagator.TraceContext invCtx = contextPropagator.createChildContext(orderCtx);
            long invStart = orderStart + 3_000_000L;
            long invDuration = (12 + random.nextInt(10)) * 1_000_000L;
            SpanRecord invSpan = createSpan(
                    invCtx,
                    "inventory-service",
                    "POST /inventory/reserve",
                    invStart,
                    invDuration,
                    StatusCode.STATUS_CODE_OK,
                    Map.of("sku.id", "sku-" + random.nextInt(500), "quantity", "1")
            );
            traceSpans.add(invSpan);

            // 3b. Inventory DB Query (Injected with 300ms - 1500ms latency during DB_LATENCY chaos)
            TraceContextPropagator.TraceContext invDbCtx = contextPropagator.createChildContext(invCtx);
            long dbDurationMs = isDbLatencyScenario ? (350 + random.nextInt(900)) : (4 + random.nextInt(4));
            long invDbDuration = dbDurationMs * 1_000_000L;
            SpanRecord invDbSpan = createSpan(
                    invDbCtx,
                    "inventory-service",
                    "SQL UPDATE inventory SET stock = stock - 1",
                    invStart + 1_000_000L,
                    invDbDuration,
                    StatusCode.STATUS_CODE_OK,
                    Map.of(
                            "db.system", "postgresql",
                            "db.statement", "UPDATE inventory SET stock = stock - 1 WHERE sku = ?",
                            "db.latency_injected", isDbLatencyScenario ? "true" : "false"
                    )
            );
            traceSpans.add(invDbSpan);

            // 4. Payment Service Span (Main bottleneck / Error Storm target)
            TraceContextPropagator.TraceContext payCtx = contextPropagator.createChildContext(orderCtx);
            long payStart = invStart + invDuration + invDbDuration + 2_000_000L;
            long payDurationMs = isCpuSpikeScenario ? 380 : (65 + random.nextInt(35));
            long payDuration = payDurationMs * 1_000_000L;
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
            traceSpans.add(paySpan);

            // 4b. Stripe External Gateway Call
            TraceContextPropagator.TraceContext stripeCtx = contextPropagator.createChildContext(payCtx);
            long stripeDuration = payDuration - 8_000_000L;
            SpanRecord stripeSpan = createSpan(
                    stripeCtx,
                    "payment-service",
                    "HTTP POST https://api.stripe.com/v1/charges",
                    payStart + 3_000_000L,
                    stripeDuration,
                    isError ? StatusCode.STATUS_CODE_ERROR : StatusCode.STATUS_CODE_OK,
                    Map.of("http.url", "https://api.stripe.com/v1/charges", "peer.service", "stripe")
            );
            traceSpans.add(stripeSpan);

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
            traceSpans.add(notifSpan);

            // Emit full trace
            spanEmitter.emitSpans(traceSpans);

        } catch (Exception e) {
            log.error("Simulation error: {}", e.getMessage(), e);
        }
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
