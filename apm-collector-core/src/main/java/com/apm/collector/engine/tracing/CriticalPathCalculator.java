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

        computeSubtreeCriticalWeight(rootSpan.getSpanId(), spanMap, parentToChildren, memo, nextCriticalChild);

        // Trace back from root along the maximal critical edge chain
        String currentSpanId = rootSpan.getSpanId();
        while (currentSpanId != null && !currentSpanId.isEmpty()) {
            criticalPathSpanIds.add(currentSpanId);
            currentSpanId = nextCriticalChild.get(currentSpanId);
        }

        return criticalPathSpanIds;
    }

    private long computeSubtreeCriticalWeight(
            String spanId,
            Map<String, SpanRecord> spanMap,
            Map<String, List<SpanRecord>> parentToChildren,
            Map<String, Long> memo,
            Map<String, String> nextCriticalChild) {

        if (memo.containsKey(spanId)) {
            return memo.get(spanId);
        }

        SpanRecord currentSpan = spanMap.get(spanId);
        long selfDuration = (currentSpan != null) ? currentSpan.getDurationNano() : 0L;

        List<SpanRecord> children = parentToChildren.getOrDefault(spanId, Collections.emptyList());
        if (children.isEmpty()) {
            memo.put(spanId, selfDuration);
            return selfDuration;
        }

        long maxChildWeight = 0L;
        String bestChildId = null;

        for (SpanRecord child : children) {
            long childWeight = computeSubtreeCriticalWeight(
                    child.getSpanId(),
                    spanMap,
                    parentToChildren,
                    memo,
                    nextCriticalChild
            );

            if (childWeight > maxChildWeight) {
                maxChildWeight = childWeight;
                bestChildId = child.getSpanId();
            }
        }

        if (bestChildId != null) {
            nextCriticalChild.put(spanId, bestChildId);
        }

        long totalWeight = selfDuration + maxChildWeight;
        memo.put(spanId, totalWeight);
        return totalWeight;
    }
}
