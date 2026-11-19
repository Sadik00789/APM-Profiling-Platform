import { SpanRecord, TraceTreeNode } from '../../types/trace';

export interface LayoutSpanItem {
  span: SpanRecord;
  depth: number;
  leftPercent: number;
  widthPercent: number;
  isCriticalPath: boolean;
  exclusiveTimePercent: number;
  hasChildren: boolean;
}

export function flattenTraceTree(
  root: TraceTreeNode | null | undefined,
  traceId: string,
  totalDurationMs: number,
  collapsedSet: Set<string> = new Set()
): LayoutSpanItem[] {
  const items: LayoutSpanItem[] = [];
  if (!root || !root.spanId) {
    return items;
  }

  flattenNode(root, 0, root.startTimeUnixNano, traceId, totalDurationMs, items, collapsedSet);
  return items;
}

function flattenNode(
  node: TraceTreeNode,
  depth: number,
  traceStartNano: number,
  traceId: string,
  totalDurationMs: number,
  results: LayoutSpanItem[],
  collapsedSet: Set<string>
): void {
  if (!node || !node.spanId) return;

  const startOffsetMs = Math.max(0, (node.startTimeUnixNano - traceStartNano) / 1_000_000.0);
  const spanDurationMs = node.durationMs || node.durationNano / 1_000_000.0;

  const rawLeft = totalDurationMs > 0 ? (startOffsetMs / totalDurationMs) * 100 : 0;
  const leftPercent = Math.min(99.5, Math.max(0, rawLeft));
  const rawWidth = totalDurationMs > 0 ? Math.max(0.5, (spanDurationMs / totalDurationMs) * 100) : 100;
  const widthPercent = Math.min(100 - leftPercent, Math.max(0.2, rawWidth));

  const spanRecord: SpanRecord = {
    traceId,
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
    leftPercent,
    widthPercent,
    isCriticalPath: node.isCriticalPath,
    exclusiveTimePercent: node.exclusiveTimePercent,
    hasChildren,
  });

  if (hasChildren && !collapsedSet.has(node.spanId)) {
    for (const child of node.children) {
      flattenNode(child, depth + 1, traceStartNano, traceId, totalDurationMs, results, collapsedSet);
    }
  }
}
