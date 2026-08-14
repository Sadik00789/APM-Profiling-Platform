'use client';

import { useState, useEffect, useCallback } from 'react';
import { TraceTreeResponse, TraceTreeNode, SpanRecord } from '../types/trace';
import { LayoutSpanItem } from '../lib/workers/trace-tree.worker';

export function useTraceWaterfall(traceId: string) {
  const [traceData, setTraceData] = useState<TraceTreeResponse | null>(null);
  const [layoutItems, setLayoutItems] = useState<LayoutSpanItem[]>([]);
  const [selectedSpan, setSelectedSpan] = useState<SpanRecord | null>(null);
  const [collapsedSpans, setCollapsedSpans] = useState<Set<string>>(new Set());
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const fetchTrace = useCallback(async () => {
    if (!traceId) return;
    setIsLoading(true);
    setError(null);
    try {
      const res = await fetch(`/api/v1/traces/${traceId}`);
      if (!res.ok) throw new Error(`Trace not found: ${res.statusText}`);
      const json: TraceTreeResponse = await res.json();
      setTraceData(json);

      if (json.root) {
        const items: LayoutSpanItem[] = [];
        flattenTree(json.root, 0, json.root.startTimeUnixNano, json.totalDurationMs, items, new Set());
        setLayoutItems(items);
        if (!selectedSpan && json.root.spanId) {
          setSelectedSpan({
            traceId: json.traceId,
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
        }
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
    const items: LayoutSpanItem[] = [];
    flattenTree(traceData.root, 0, traceData.root.startTimeUnixNano, traceData.totalDurationMs, items, collapsedSpans);
    setLayoutItems(items);
  }, [traceData, collapsedSpans]);

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

function flattenTree(
  node: TraceTreeNode,
  depth: number,
  traceStartNano: number,
  totalDurationMs: number,
  results: LayoutSpanItem[],
  collapsedSet: Set<string>
) {
  if (!node || !node.spanId) return;

  const startOffsetMs = Math.max(0, (node.startTimeUnixNano - traceStartNano) / 1_000_000.0);
  const spanDurationMs = node.durationMs || node.durationNano / 1_000_000.0;

  const leftPercent = totalDurationMs > 0 ? (startOffsetMs / totalDurationMs) * 100 : 0;
  const widthPercent = totalDurationMs > 0 ? Math.max(0.5, (spanDurationMs / totalDurationMs) * 100) : 100;

  const spanRecord: SpanRecord = {
    traceId: '',
    spanId: node.spanId,
    parentSpanId: node.parentSpanId,
    serviceName: node.serviceName,
    operationName: node.operationName,
    startTimeUnixNano: node.startTimeUnixNano,
    durationMs: spanDurationMs,
    durationNano: node.durationNano,
    statusCode: node.statusCode,
    attributes: node.attributes,
  };

  const hasChildren = Boolean(node.children && node.children.length > 0);

  results.push({
    span: spanRecord,
    depth,
    leftPercent: Math.min(100, Math.max(0, leftPercent)),
    widthPercent: Math.min(100 - leftPercent, Math.max(0.2, widthPercent)),
    isCriticalPath: node.isCriticalPath,
    exclusiveTimePercent: node.exclusiveTimePercent,
    hasChildren,
  });

  if (hasChildren && !collapsedSet.has(node.spanId)) {
    for (const child of node.children) {
      flattenTree(child, depth + 1, traceStartNano, totalDurationMs, results, collapsedSet);
    }
  }
}
