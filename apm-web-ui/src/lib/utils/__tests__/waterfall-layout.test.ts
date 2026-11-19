import { describe, expect, it } from 'vitest';
import { flattenTraceTree } from '../waterfall-layout';
import { TraceTreeNode } from '../../../types/trace';

const sampleTree: TraceTreeNode = {
  spanId: 'root-span',
  parentSpanId: '',
  serviceName: 'api-gateway',
  operationName: 'POST /checkout',
  startTimeUnixNano: 1_000_000_000,
  durationMs: 100,
  durationNano: 100_000_000,
  statusCode: 'STATUS_CODE_OK',
  isCriticalPath: true,
  exclusiveTimePercent: 20,
  depth: 0,
  attributes: {},
  children: [
    {
      spanId: 'child-1',
      parentSpanId: 'root-span',
      serviceName: 'order-service',
      operationName: 'POST /orders',
      startTimeUnixNano: 1_010_000_000,
      durationMs: 80,
      durationNano: 80_000_000,
      statusCode: 'STATUS_CODE_OK',
      isCriticalPath: true,
      exclusiveTimePercent: 30,
      depth: 1,
      attributes: {},
      children: [
        {
          spanId: 'grandchild-1',
          parentSpanId: 'child-1',
          serviceName: 'payment-service',
          operationName: 'POST /charge',
          startTimeUnixNano: 1_020_000_000,
          durationMs: 50,
          durationNano: 50_000_000,
          statusCode: 'STATUS_CODE_ERROR',
          isCriticalPath: true,
          exclusiveTimePercent: 50,
          depth: 2,
          attributes: {},
          children: [],
        },
      ],
    },
    {
      spanId: 'skewed-late',
      parentSpanId: 'root-span',
      serviceName: 'notification-service',
      operationName: 'POST /email',
      startTimeUnixNano: 1_200_000_000, // 200ms after trace start, beyond 100ms totalDuration
      durationMs: 25,
      durationNano: 25_000_000,
      statusCode: 'STATUS_CODE_OK',
      isCriticalPath: false,
      exclusiveTimePercent: 0,
      depth: 1,
      attributes: {},
      children: [],
    },
  ],
};

describe('waterfall-layout', () => {
  it('propagates traceId and keeps leftPercent + widthPercent bounded within [0, 100]', () => {
    const items = flattenTraceTree(sampleTree, 'trace-xyz', 100);
    expect(items).toHaveLength(4);

    for (const item of items) {
      expect(item.span.traceId).toBe('trace-xyz');
      expect(item.leftPercent).toBeGreaterThanOrEqual(0);
      expect(item.leftPercent).toBeLessThanOrEqual(99.5);
      expect(item.widthPercent).toBeGreaterThanOrEqual(0.2);
      expect(item.leftPercent + item.widthPercent).toBeLessThanOrEqual(100);
    }

    const lateItem = items.find((i) => i.span.spanId === 'skewed-late')!;
    expect(lateItem.leftPercent).toBe(99.5);
    expect(lateItem.widthPercent).toBeCloseTo(0.5);
  });

  it('hides descendants of collapsed spans while retaining parent hasChildren flag', () => {
    const items = flattenTraceTree(sampleTree, 'trace-xyz', 100, new Set(['child-1']));
    expect(items.map((i) => i.span.spanId)).toEqual(['root-span', 'child-1', 'skewed-late']);
    const collapsed = items.find((i) => i.span.spanId === 'child-1')!;
    expect(collapsed.hasChildren).toBe(true);
  });
});
