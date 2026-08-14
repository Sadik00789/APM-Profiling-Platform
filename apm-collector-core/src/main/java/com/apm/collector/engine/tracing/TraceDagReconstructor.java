package com.apm.collector.engine.tracing;

import com.apm.contracts.trace.v1.SpanRecord;
import com.apm.contracts.trace.v1.StatusCode;
import com.apm.contracts.trace.v1.TraceTreeNode;
import com.apm.contracts.trace.v1.TraceTreeResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
@RequiredArgsConstructor
public class TraceDagReconstructor {

    private final CriticalPathCalculator criticalPathCalculator;
    private static final long CLOCK_SKEW_MIN_DELTA_NANO = 1_000L; // 1 microsecond

    public TraceTreeResponse reconstruct(List<SpanRecord> rawSpans) {
        if (rawSpans == null || rawSpans.isEmpty()) {
            return TraceTreeResponse.getDefaultInstance();
        }

        String traceId = rawSpans.get(0).getTraceId();

        // 1. Initial Pass: Map spans and group children
        Map<String, SpanRecord> spanMap = new HashMap<>();
        Map<String, List<SpanRecord>> parentToChildren = new HashMap<>();
        Set<String> services = new TreeSet<>();
        int errorCount = 0;

        for (SpanRecord span : rawSpans) {
            spanMap.put(span.getSpanId(), span);
            services.add(span.getServiceName());
            if (span.getStatusCode() == StatusCode.STATUS_CODE_ERROR) {
                errorCount++;
            }
            String parentId = span.getParentSpanId();
            if (parentId == null) parentId = "";
            parentToChildren.computeIfAbsent(parentId, k -> new ArrayList<>()).add(span);
        }

        // 2. Identify Root Span
        SpanRecord rootSpan = null;
        for (SpanRecord span : rawSpans) {
            String pid = span.getParentSpanId();
            if (pid == null || pid.isEmpty() || !spanMap.containsKey(pid)) {
                rootSpan = span;
                break;
            }
        }

        if (rootSpan == null) {
            rootSpan = rawSpans.stream().min(Comparator.comparingLong(SpanRecord::getStartTimeUnixNano)).orElse(rawSpans.get(0));
        }

        // 3. Distributed Clock Skew Correction (Top-Down BFS/DFS traversal)
        Map<String, SpanRecord> adjustedSpanMap = new HashMap<>();
        correctClockSkewRecursive(rootSpan, null, parentToChildren, spanMap, adjustedSpanMap);

        // Recalculate min start and max end from adjusted spans
        long minStartTime = Long.MAX_VALUE;
        long maxEndTime = Long.MIN_VALUE;

        for (SpanRecord span : adjustedSpanMap.values()) {
            long start = span.getStartTimeUnixNano();
            long end = span.getEndTimeUnixNano();
            if (start < minStartTime) minStartTime = start;
            if (end > maxEndTime) maxEndTime = end;
        }

        long totalDurationNano = (maxEndTime > minStartTime && minStartTime != Long.MAX_VALUE) ? (maxEndTime - minStartTime) : rootSpan.getDurationNano();

        // 4. Identify Critical Path Span IDs
        Set<String> criticalSpanIds = criticalPathCalculator.computeCriticalPath(
                adjustedSpanMap.get(rootSpan.getSpanId()),
                adjustedSpanMap,
                parentToChildren
        );

        // 5. Build Tree Nodes
        TraceTreeNode rootNode = buildTreeNode(
                adjustedSpanMap.get(rootSpan.getSpanId()),
                parentToChildren,
                adjustedSpanMap,
                criticalSpanIds,
                0,
                totalDurationNano
        );

        return TraceTreeResponse.newBuilder()
                .setTraceId(traceId)
                .setRoot(rootNode)
                .setTotalDurationNano(totalDurationNano)
                .setTotalSpans(adjustedSpanMap.size())
                .setCriticalPathSpans(criticalSpanIds.size())
                .setErrorCount(errorCount)
                .addAllServicesInvolved(services)
                .build();
    }

    private void correctClockSkewRecursive(
            SpanRecord current,
            SpanRecord parent,
            Map<String, List<SpanRecord>> parentToChildren,
            Map<String, SpanRecord> originalMap,
            Map<String, SpanRecord> adjustedMap) {

        SpanRecord adjusted = current;

        if (parent != null) {
            long parentStart = parent.getStartTimeUnixNano();
            long childStart = current.getStartTimeUnixNano();

            // Detect if NTP clock drift caused child to start before parent
            if (childStart < parentStart) {
                long correctedStart = parentStart + CLOCK_SKEW_MIN_DELTA_NANO;
                long duration = current.getDurationNano();
                long correctedEnd = correctedStart + duration;

                Map<String, String> newAttrs = new HashMap<>(current.getAttributesMap());
                newAttrs.put("meta.clock_skew_adjusted", "true");
                newAttrs.put("meta.clock_skew_drift_ns", String.valueOf(parentStart - childStart));

                adjusted = current.toBuilder()
                        .setStartTimeUnixNano(correctedStart)
                        .setEndTimeUnixNano(correctedEnd)
                        .clearAttributes()
                        .putAllAttributes(newAttrs)
                        .build();
            }
        }

        adjustedMap.put(adjusted.getSpanId(), adjusted);

        List<SpanRecord> children = parentToChildren.getOrDefault(current.getSpanId(), Collections.emptyList());
        for (SpanRecord child : children) {
            correctClockSkewRecursive(child, adjusted, parentToChildren, originalMap, adjustedMap);
        }
    }

    private TraceTreeNode buildTreeNode(
            SpanRecord span,
            Map<String, List<SpanRecord>> parentToChildren,
            Map<String, SpanRecord> adjustedSpanMap,
            Set<String> criticalSpanIds,
            int depth,
            long totalTraceDurationNano) {

        List<SpanRecord> childrenSpans = parentToChildren.getOrDefault(span.getSpanId(), Collections.emptyList());
        List<SpanRecord> adjustedChildren = new ArrayList<>();
        for (SpanRecord child : childrenSpans) {
            SpanRecord adj = adjustedSpanMap.getOrDefault(child.getSpanId(), child);
            adjustedChildren.add(adj);
        }
        adjustedChildren.sort(Comparator.comparingLong(SpanRecord::getStartTimeUnixNano));

        long childrenTotalDuration = 0;
        List<TraceTreeNode> childNodes = new ArrayList<>();
        for (SpanRecord child : adjustedChildren) {
            childrenTotalDuration += child.getDurationNano();
            childNodes.add(buildTreeNode(child, parentToChildren, adjustedSpanMap, criticalSpanIds, depth + 1, totalTraceDurationNano));
        }

        long selfDurationNano = Math.max(0, span.getDurationNano() - childrenTotalDuration);
        double exclusivePercent = (totalTraceDurationNano > 0)
                ? ((double) selfDurationNano / totalTraceDurationNano * 100.0)
                : 0.0;

        boolean isCritical = criticalSpanIds.contains(span.getSpanId());

        return TraceTreeNode.newBuilder()
                .setSpan(span)
                .addAllChildren(childNodes)
                .setIsCriticalPath(isCritical)
                .setExclusiveTimePercent(Math.round(exclusivePercent * 100.0) / 100.0)
                .setDepth(depth)
                .setCriticalPathSelfTimeNano(isCritical ? selfDurationNano : 0)
                .build();
    }
}
