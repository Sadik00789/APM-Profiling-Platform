export type StatusCode = 'STATUS_CODE_UNSET' | 'STATUS_CODE_OK' | 'STATUS_CODE_ERROR';

export type SpanKind = 'SPAN_KIND_INTERNAL' | 'SPAN_KIND_SERVER' | 'SPAN_KIND_CLIENT' | 'SPAN_KIND_PRODUCER' | 'SPAN_KIND_CONSUMER';

export interface SpanRecord {
  traceId: string;
  spanId: string;
  parentSpanId: string;
  serviceName: string;
  operationName: string;
  startTimeUnixNano: number;
  durationMs: number;
  durationNano: number;
  statusCode: StatusCode;
  attributes?: Record<string, string>;
  timestamp?: number;
}

export interface TraceTreeNode {
  spanId: string;
  parentSpanId: string;
  serviceName: string;
  operationName: string;
  startTimeUnixNano: number;
  durationMs: number;
  durationNano: number;
  statusCode: StatusCode;
  isCriticalPath: boolean;
  exclusiveTimePercent: number;
  depth: number;
  attributes: Record<string, string>;
  children: TraceTreeNode[];
}

export interface TraceTreeResponse {
  traceId: string;
  totalDurationMs: number;
  totalSpans: number;
  criticalPathSpans: number;
  errorCount: number;
  servicesInvolved: string[];
  root: TraceTreeNode;
  found: boolean;
}

export interface ServiceTopologyNode {
  id: string;
  name: string;
  type: 'GATEWAY' | 'SERVICE' | 'DATABASE' | 'BROKER';
  status: 'healthy' | 'degraded' | 'critical';
  rps: number;
  p95Ms: number;
  errorRatePercent: number;
  x?: number;
  y?: number;
}

export interface ServiceTopologyEdge {
  source: string;
  target: string;
  callCount: number;
  errorCount: number;
  rps: number;
  avgLatencyMs: number;
}

export interface ServiceTopologyResponse {
  nodes: ServiceTopologyNode[];
  edges: ServiceTopologyEdge[];
  timestamp: number;
}

export interface ServiceHealthSummary {
  serviceName: string;
  rps: number;
  p50Ms: number;
  p95Ms: number;
  p99Ms: number;
  errorRatePercent: number;
  status: 'HEALTHY' | 'DEGRADED' | 'CRITICAL';
}

export interface GlobalHealthResponse {
  clusterRps: number;
  globalErrorRatePercent: number;
  activeServicesCount: number;
  services: ServiceHealthSummary[];
  timestamp: number;
}
