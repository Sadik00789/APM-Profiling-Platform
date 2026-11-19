package com.apm.collector.engine.tracing;

import com.apm.contracts.trace.v1.SpanRecord;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class CriticalPathCalculator {

    /**
     * Computes the critical execution path of a distributed trace DAG using
     * longest-path dynamic programming over dependent span nodes.
     */
    public Set<String> computeCriticalPath(
            SpanRecord rootSpan,
            Map<String, SpanRecord> spanMap,
            Map<String, List<SpanRecord>> parentToChildren) {

        Set<String> criticalPathSpanIds = new HashSet<>();
        if (rootSpan == null) {
            return criticalPathSpanIds;
        }

        Map<String, Long> memo = new HashMap<>();
        Map<String, String> nextCriticalChild = new HashMap<>();
        Set<String> visiting = new HashSet<>();

        computeSubtreeCriticalWeight(rootSpan.getSpanId(), spanMap, parentToChildren, memo, nextCriticalChild, visiting);

        // Trace back from root along the maximal critical edge chain (cycle-guarded)
        String currentSpanId = rootSpan.getSpanId();
        while (currentSpanId != null && !currentSpanId.isEmpty() && criticalPathSpanIds.add(currentSpanId)) {
            currentSpanId = nextCriticalChild.get(currentSpanId);
        }

        return criticalPathSpanIds;
    }

    private long computeSubtreeCriticalWeight(
            String spanId,
            Map<String, SpanRecord> spanMap,
            Map<String, List<SpanRecord>> parentToChildren,
            Map<String, Long> memo,
            Map<String, String> nextCriticalChild,
            Set<String> visiting) {

        if (memo.containsKey(spanId)) {
            return memo.get(spanId);
        }
        if (!visiting.add(spanId)) {
            return 0L;
        }

        SpanRecord currentSpan = spanMap.get(spanId);
        long spanDuration = (currentSpan != null) ? currentSpan.getDurationNano() : 0L;

        List<SpanRecord> children = parentToChildren.getOrDefault(spanId, Collections.emptyList());
        if (children.isEmpty()) {
            memo.put(spanId, spanDuration);
            visiting.remove(spanId);
            return spanDuration;
        }

        long maxChildWeight = -1L;
        long bestChildEnd = Long.MIN_VALUE;
        String bestChildId = null;

        for (SpanRecord child : children) {
            String childId = child.getSpanId();
            if (visiting.contains(childId)) {
                continue;
            }
            long childWeight = computeSubtreeCriticalWeight(
                    childId,
                    spanMap,
                    parentToChildren,
                    memo,
                    nextCriticalChild,
                    visiting
            );

            SpanRecord resolvedChild = spanMap.getOrDefault(childId, child);
            long childEnd = resolvedChild != null ? resolvedChild.getEndTimeUnixNano() : 0L;

            if (childWeight > maxChildWeight || (childWeight == maxChildWeight && childEnd > bestChildEnd)) {
                maxChildWeight = childWeight;
                bestChildEnd = childEnd;
                bestChildId = childId;
            }
        }

        if (bestChildId != null) {
            nextCriticalChild.put(spanId, bestChildId);
        }

        long effectiveWeight = Math.max(spanDuration, maxChildWeight);
        memo.put(spanId, effectiveWeight);
        visiting.remove(spanId);
        return effectiveWeight;
    }
}
