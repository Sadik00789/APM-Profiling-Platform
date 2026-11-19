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

        // 2. Identify Root Span (prefer empty parentSpanId, earliest start time) & attach disconnected orphans
        SpanRecord rootSpan = spanMap.values().stream()
                .filter(s -> s.getParentSpanId() == null || s.getParentSpanId().isEmpty())
                .min(Comparator.comparingLong(SpanRecord::getStartTimeUnixNano))
                .orElse(null);

        if (rootSpan == null) {
            rootSpan = spanMap.values().stream()
                    .filter(s -> !spanMap.containsKey(s.getParentSpanId()))
                    .min(Comparator.comparingLong(SpanRecord::getStartTimeUnixNano))
                    .orElse(null);
        }

        if (rootSpan == null) {
            rootSpan = spanMap.values().stream()
                    .min(Comparator.comparingLong(SpanRecord::getStartTimeUnixNano))
                    .orElse(rawSpans.get(0));
        }

        String rootId = rootSpan.getSpanId();
        for (SpanRecord candidate : spanMap.values()) {
            if (candidate.getSpanId().equals(rootId)) {
                continue;
            }
            String pid = candidate.getParentSpanId();
            if (pid == null || pid.isEmpty() || !spanMap.containsKey(pid)) {
                parentToChildren.computeIfAbsent(rootId, k -> new ArrayList<>()).add(candidate);
            }
        }

        // 3. Distributed Clock Skew Correction (Top-Down DFS traversal with cycle guard)
        Map<String, SpanRecord> adjustedSpanMap = new HashMap<>();
        correctClockSkewRecursive(rootSpan, null, parentToChildren, adjustedSpanMap, new HashSet<>());

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
                totalDurationNano,
                new HashSet<>()
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
            Map<String, SpanRecord> adjustedMap,
            Set<String> visited) {

        if (current == null || !visited.add(current.getSpanId())) {
            return;
        }

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
            correctClockSkewRecursive(child, adjusted, parentToChildren, adjustedMap, visited);
        }
    }

    private TraceTreeNode buildTreeNode(
            SpanRecord span,
            Map<String, List<SpanRecord>> parentToChildren,
            Map<String, SpanRecord> adjustedSpanMap,
            Set<String> criticalSpanIds,
            int depth,
            long totalTraceDurationNano,
            Set<String> visited) {

        visited.add(span.getSpanId());

        List<SpanRecord> childrenSpans = parentToChildren.getOrDefault(span.getSpanId(), Collections.emptyList());
        List<SpanRecord> adjustedChildren = new ArrayList<>();
        for (SpanRecord child : childrenSpans) {
            if (!visited.contains(child.getSpanId())) {
                SpanRecord adj = adjustedSpanMap.getOrDefault(child.getSpanId(), child);
                adjustedChildren.add(adj);
            }
        }
        adjustedChildren.sort(Comparator.comparingLong(SpanRecord::getStartTimeUnixNano));

        long spanStart = span.getStartTimeUnixNano();
        long spanEnd = span.getEndTimeUnixNano() > spanStart
                ? span.getEndTimeUnixNano()
                : spanStart + span.getDurationNano();

        // Compute interval union of child spans so parallel children are not double-counted
        long coveredChildDurationNano = 0L;
        long activeIntervalStart = -1L;
        long activeIntervalEnd = -1L;

        List<TraceTreeNode> childNodes = new ArrayList<>();
        for (SpanRecord child : adjustedChildren) {
            if (visited.contains(child.getSpanId())) {
                continue;
            }
            long cStart = Math.max(spanStart, child.getStartTimeUnixNano());
            long childRawEnd = child.getEndTimeUnixNano() > child.getStartTimeUnixNano()
                    ? child.getEndTimeUnixNano()
                    : child.getStartTimeUnixNano() + child.getDurationNano();
            long cEnd = Math.min(spanEnd, childRawEnd);
            if (cEnd > cStart) {
                if (activeIntervalStart == -1L) {
                    activeIntervalStart = cStart;
                    activeIntervalEnd = cEnd;
                } else if (cStart <= activeIntervalEnd) {
                    activeIntervalEnd = Math.max(activeIntervalEnd, cEnd);
                } else {
                    coveredChildDurationNano += (activeIntervalEnd - activeIntervalStart);
                    activeIntervalStart = cStart;
                    activeIntervalEnd = cEnd;
                }
            }
            childNodes.add(buildTreeNode(child, parentToChildren, adjustedSpanMap, criticalSpanIds, depth + 1, totalTraceDurationNano, visited));
        }
        if (activeIntervalStart != -1L) {
            coveredChildDurationNano += (activeIntervalEnd - activeIntervalStart);
        }

        long selfDurationNano = Math.max(0L, span.getDurationNano() - coveredChildDurationNano);
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
