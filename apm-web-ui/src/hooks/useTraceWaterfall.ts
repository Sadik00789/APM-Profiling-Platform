'use client';

import { useState, useEffect, useCallback } from 'react';
import { TraceTreeResponse, SpanRecord } from '../types/trace';
import { flattenTraceTree, LayoutSpanItem } from '../lib/utils/waterfall-layout';

export function useTraceWaterfall(traceId: string) {
  const [traceData, setTraceData] = useState<TraceTreeResponse | null>(null);
  const [layoutItems, setLayoutItems] = useState<LayoutSpanItem[]>([]);
  const [selectedSpan, setSelectedSpan] = useState<SpanRecord | null>(null);
  const [collapsedSpans, setCollapsedSpans] = useState<Set<string>>(new Set());
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    setSelectedSpan(null);
    setCollapsedSpans(new Set());
  }, [traceId]);

  const fetchTrace = useCallback(async () => {
    if (!traceId) return;
    setIsLoading(true);
    setError(null);
    try {
      const res = await fetch(`/api/v1/traces/${encodeURIComponent(traceId)}`);
      if (!res.ok) throw new Error(`Trace not found: ${res.statusText}`);
      const json: TraceTreeResponse = await res.json();
      setTraceData(json);

      if (json.root) {
        const items = flattenTraceTree(json.root, json.traceId || traceId, json.totalDurationMs, new Set());
        setLayoutItems(items);
        if (json.root.spanId) {
          setSelectedSpan({
            traceId: json.traceId || traceId,
            spanId: json.root.spanId,
            parentSpanId: json.root.parentSpanId,
            serviceName: json.root.serviceName,
            operationName: json.root.operationName,
            startTimeUnixNano: json.root.startTimeUnixNano,
            durationMs: json.root.durationMs,
            durationNano: json.root.durationNano,
            statusCode: json.root.statusCode,
            attributes: json.root.attributes,
          });
        } else {
          setSelectedSpan(null);
        }
      } else {
        setLayoutItems([]);
        setSelectedSpan(null);
      }
    } catch (err: any) {
      console.error('Error loading trace:', err);
      setError(err.message || 'Failed to load trace');
    } finally {
      setIsLoading(false);
    }
  }, [traceId]);

  useEffect(() => {
    fetchTrace();
  }, [fetchTrace]);

  const toggleCollapse = useCallback((spanId: string) => {
    setCollapsedSpans((prev) => {
      const next = new Set(prev);
      if (next.has(spanId)) next.delete(spanId);
      else next.add(spanId);
      return next;
    });
  }, []);

  // Re-flatten when collapsed spans change
  useEffect(() => {
    if (!traceData?.root) return;
    setLayoutItems(
      flattenTraceTree(traceData.root, traceData.traceId || traceId, traceData.totalDurationMs, collapsedSpans)
    );
  }, [traceData, traceId, collapsedSpans]);

  return {
    traceData,
    layoutItems,
    selectedSpan,
    collapsedSpans,
    isLoading,
    error,
    setSelectedSpan,
    toggleCollapse,
    refetch: fetchTrace,
  };
}
