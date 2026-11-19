import { flattenTraceTree, LayoutSpanItem } from '../utils/waterfall-layout';

export type { LayoutSpanItem };

// Web Worker for off-thread Trace DAG calculation and Gantt layout positioning

self.onmessage = (event: MessageEvent) => {
  const { type, payload } = event.data;

  if (type === 'COMPUTE_WATERFALL_LAYOUT') {
    const { root, traceId = '', totalDurationMs } = payload;
    const items = flattenTraceTree(root, traceId, totalDurationMs);

    self.postMessage({ type: 'LAYOUT_COMPLETE', items });
  }
};
