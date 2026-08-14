import { SpanRecord, TraceTreeNode } from '../../types/trace';

// Web Worker for off-thread Trace DAG calculation and Gantt layout positioning

export interface LayoutSpanItem {
  span: SpanRecord;
  depth: number;
  leftPercent: number;
  widthPercent: number;
  isCriticalPath: boolean;
  exclusiveTimePercent: number;
  hasChildren: boolean;
}

self.onmessage = (event: MessageEvent) => {
  const { type, payload } = event.data;

  if (type === 'COMPUTE_WATERFALL_LAYOUT') {
    const { root, totalDurationMs } = payload;
    const items: LayoutSpanItem[] = [];

    if (root && root.spanId) {
      flattenTreeForWaterfall(root, 0, root.startTimeUnixNano, totalDurationMs, items);
    }

    self.postMessage({ type: 'LAYOUT_COMPLETE', items });
  }
};

function flattenTreeForWaterfall(
  node: TraceTreeNode,
  depth: number,
  traceStartNano: number,
  totalDurationMs: number,
  results: LayoutSpanItem[]
) {
  const spanStartNano = node.startTimeUnixNano;
  const spanDurationNano = node.durationNano;

  const startOffsetMs = Math.max(0, (spanStartNano - traceStartNano) / 1_000_000.0);
  const spanDurationMs = node.durationMs || spanDurationNano / 1_000_000.0;

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

  results.push({
    span: spanRecord,
    depth,
    leftPercent: Math.min(100, Math.max(0, leftPercent)),
    widthPercent: Math.min(100 - leftPercent, Math.max(0.2, widthPercent)),
    isCriticalPath: node.isCriticalPath,
    exclusiveTimePercent: node.exclusiveTimePercent,
    hasChildren: Boolean(node.children && node.children.length > 0),
  });

  if (node.children) {
    for (const child of node.children) {
      flattenTreeForWaterfall(child, depth + 1, traceStartNano, totalDurationMs, results);
    }
  }
}
