export type ProfileType = 'CPU' | 'WALL' | 'ALLOC_SPACE' | 'ALLOC_OBJECTS' | 'LOCK_TIME' | 'LOCK_COUNT';

export interface FlameGraphNode {
  name: string;
  package: string;
  value: number;
  selfValue: number;
  selfPercent: number;
  totalPercent: number;
  depth: number;
  diffValue?: number;
  diffPercent?: number;
  children?: FlameGraphNode[];
  // D3 hierarchy layout calculated fields
  x0?: number;
  x1?: number;
  y0?: number;
  y1?: number;
}

export interface FlameGraphResponse {
  serviceName: string;
  profileType: ProfileType;
  fromTimestampSec: number;
  untilTimestampSec: number;
  totalSamples: number;
  maxDepth: number;
  totalNodes: number;
  root: FlameGraphNode;
}

export interface DiffFlameGraphResponse {
  serviceName: string;
  profileType: ProfileType;
  baselineTotal: number;
  comparisonTotal: number;
  overallChangePercent: number;
  root: FlameGraphNode;
}

export interface HistogramBucket {
  range: string;
  count: number;
  min: number;
  max: number;
}

export interface LatencyHistogramResponse {
  serviceName: string;
  p50Ms: number;
  p95Ms: number;
  p99Ms: number;
  buckets: HistogramBucket[];
}
