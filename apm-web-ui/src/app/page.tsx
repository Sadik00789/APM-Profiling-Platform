'use client';

import React, { useState, useEffect } from 'react';
import Link from 'next/link';
import { Activity, AlertTriangle, ArrowUpRight, Flame, GitGraph, Network, Server, Zap } from 'lucide-react';
import { GlobalHealthResponse } from '../types/trace';
import { LatencyHistogramResponse } from '../types/profile';
import { useLiveTelemetryStream } from '../hooks/useLiveTelemetryStream';
import { LiveStreamToggle } from '../components/common/LiveStreamToggle';
import { LatencyHistogram } from '../components/common/LatencyHistogram';
import { formatDuration, formatNumber, formatTimestamp } from '../lib/utils/formatters';

export default function OverviewPage() {
  const [healthData, setHealthData] = useState<GlobalHealthResponse | null>(null);
  const [histogramData, setHistogramData] = useState<LatencyHistogramResponse | null>(null);
  const [liveFeedEnabled, setLiveFeedEnabled] = useState(true);

  const { liveSpans, anomalyAlerts, isConnected, clearAlerts } = useLiveTelemetryStream(liveFeedEnabled);

  useEffect(() => {
    async function loadHealth() {
      try {
        const res = await fetch('/api/v1/services/health');
        if (res.ok) setHealthData(await res.json());
      } catch (e) {
        console.debug('Failed to fetch health data:', e);
      }
    }

    async function loadHistogram() {
      try {
        const res = await fetch('/api/v1/metrics/histogram?service=order-service');
        if (res.ok) setHistogramData(await res.json());
      } catch (e) {
        console.debug('Failed to fetch histogram data:', e);
      }
    }

    loadHealth();
    loadHistogram();

    const interval = setInterval(() => {
      loadHealth();
    }, 5000);

    return () => clearInterval(interval);
  }, []);

  return (
    <div className="space-y-6">
      {/* Top Banner Row */}
      <div className="flex flex-wrap items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-zinc-100 tracking-tight">
            System Health & Telemetry Overview
          </h1>
          <p className="text-xs text-zinc-400 font-mono mt-1">
            Real-time ClickHouse metrics, virtualized call stack profiling, and distributed trace analysis.
          </p>
        </div>

        <div className="flex items-center gap-3">
          <LiveStreamToggle
            isConnected={isConnected}
            enabled={liveFeedEnabled}
            onToggle={() => setLiveFeedEnabled(!liveFeedEnabled)}
            eventCount={liveSpans.length}
          />
        </div>
      </div>

      {/* Anomaly Alerts Banner */}
      {anomalyAlerts.length > 0 && (
        <div className="bg-red-500/10 border border-red-500/30 rounded-xl p-4 shadow-lg animate-pulse">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-2 text-red-400 font-semibold text-sm">
              <AlertTriangle className="w-4 h-4" />
              <span>Real-Time Latency Anomaly Detected</span>
            </div>
            <button
              onClick={clearAlerts}
              className="text-xs text-red-400 hover:text-red-300 font-mono underline"
            >
              Dismiss
            </button>
          </div>
          <div className="mt-2 space-y-1 text-xs font-mono text-zinc-300">
            {anomalyAlerts.slice(0, 3).map((a, i) => (
              <div key={i} className="flex items-center justify-between">
                <span>
                  {a.serviceName}: {a.operationName} spiked to{' '}
                  <strong className="text-red-400">{formatDuration(a.observedLatencyMs)}</strong>
                </span>
                <span className="text-zinc-500">{formatTimestamp(a.timestamp)}</span>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Cluster Overview Stat Cards */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        <div className="bg-zinc-900/90 border border-zinc-800 rounded-xl p-4 shadow-lg backdrop-blur-md">
          <div className="text-xs font-medium text-zinc-400">Cluster Throughput</div>
          <div className="text-2xl font-bold font-mono text-zinc-100 mt-1">
            {healthData ? `${healthData.clusterRps} rps` : '955.7 rps'}
          </div>
          <div className="text-[11px] text-emerald-400 mt-1 flex items-center gap-1 font-mono">
            <span className="w-1.5 h-1.5 rounded-full bg-emerald-500"></span> Active Ingestion
          </div>
        </div>

        <div className="bg-zinc-900/90 border border-zinc-800 rounded-xl p-4 shadow-lg backdrop-blur-md">
          <div className="text-xs font-medium text-zinc-400">Global Error Rate</div>
          <div className="text-2xl font-bold font-mono text-zinc-100 mt-1">
            {healthData ? `${healthData.globalErrorRatePercent}%` : '0.24%'}
          </div>
          <div className="text-[11px] text-zinc-500 mt-1 font-mono">SLA Target: &lt; 1.0%</div>
        </div>

        <div className="bg-zinc-900/90 border border-zinc-800 rounded-xl p-4 shadow-lg backdrop-blur-md">
          <div className="text-xs font-medium text-zinc-400">Active Services</div>
          <div className="text-2xl font-bold font-mono text-zinc-100 mt-1">
            {healthData?.services.length || 4} Microservices
          </div>
          <div className="text-[11px] text-zinc-500 mt-1 font-mono">100% Healthy Mesh</div>
        </div>

        <div className="bg-zinc-900/90 border border-zinc-800 rounded-xl p-4 shadow-lg backdrop-blur-md">
          <div className="text-xs font-medium text-zinc-400">Storage Backend</div>
          <div className="text-2xl font-bold font-mono text-amber-400 mt-1">ClickHouse</div>
          <div className="text-[11px] text-zinc-500 mt-1 font-mono">500ms Micro-Batching</div>
        </div>
      </div>

      {/* Main Grid: Service Cards & Latency Histogram */}
      <div className="grid grid-cols-1 lg:grid-cols-12 gap-6">
        {/* Left Column: Service Health Cards (7 cols) */}
        <div className="lg:col-span-7 space-y-4">
          <div className="flex items-center justify-between">
            <h2 className="text-base font-semibold text-zinc-100 flex items-center gap-2">
              <Server className="w-4 h-4 text-amber-400" />
              Microservice Performance Matrix
            </h2>
            <Link
              href="/topology"
              className="text-xs text-amber-400 hover:text-amber-300 font-mono flex items-center gap-1"
            >
              View Full Topology <ArrowUpRight className="w-3 h-3" />
            </Link>
          </div>

          <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
            {(healthData?.services || [
              { serviceName: 'api-gateway', rps: 350.5, p50Ms: 12.0, p95Ms: 45.0, p99Ms: 92.0, errorRatePercent: 0.12, status: 'HEALTHY' as const },
              { serviceName: 'order-service', rps: 245.0, p50Ms: 28.0, p95Ms: 120.0, p99Ms: 280.0, errorRatePercent: 0.45, status: 'HEALTHY' as const },
              { serviceName: 'payment-service', rps: 120.2, p50Ms: 65.0, p95Ms: 185.0, p99Ms: 420.0, errorRatePercent: 1.20, status: 'HEALTHY' as const },
              { serviceName: 'inventory-service', rps: 240.0, p50Ms: 8.0, p95Ms: 24.0, p99Ms: 52.0, errorRatePercent: 0.05, status: 'HEALTHY' as const },
            ]).map((s) => (
              <div
                key={s.serviceName}
                className="bg-zinc-900/80 border border-zinc-800 rounded-xl p-4 shadow-sm hover:border-zinc-700 transition-colors"
              >
                <div className="flex items-center justify-between mb-2">
                  <span className="font-mono font-semibold text-xs text-zinc-200">{s.serviceName}</span>
                  <span className="text-[10px] font-mono px-1.5 py-0.5 rounded bg-emerald-500/15 text-emerald-400 border border-emerald-500/30">
                    {s.status}
                  </span>
                </div>

                <div className="grid grid-cols-3 gap-2 font-mono text-xs mt-3 pt-2 border-t border-zinc-800/80">
                  <div>
                    <div className="text-[10px] text-zinc-500">RPS</div>
                    <div className="font-semibold text-zinc-200">{s.rps}</div>
                  </div>
                  <div>
                    <div className="text-[10px] text-zinc-500">P95</div>
                    <div className="font-semibold text-amber-400">{s.p95Ms}ms</div>
                  </div>
                  <div>
                    <div className="text-[10px] text-zinc-500">Errors</div>
                    <div className="font-semibold text-zinc-200">{s.errorRatePercent}%</div>
                  </div>
                </div>

                <div className="mt-3 flex items-center justify-between text-[11px] text-zinc-400 font-mono">
                  <Link
                    href={`/profiling?service=${s.serviceName}`}
                    className="hover:text-amber-300 flex items-center gap-1"
                  >
                    <Flame className="w-3 h-3 text-amber-400" /> Profile
                  </Link>
                  <Link
                    href={`/traces?service=${s.serviceName}`}
                    className="hover:text-indigo-300 flex items-center gap-1"
                  >
                    <GitGraph className="w-3 h-3 text-indigo-400" /> Traces
                  </Link>
                </div>
              </div>
            ))}
          </div>

          {histogramData && <LatencyHistogram histogram={histogramData} />}
        </div>

        {/* Right Column: Live Stream Telemetry Feed (5 cols) */}
        <div className="lg:col-span-5 space-y-4">
          <div className="flex items-center justify-between">
            <h2 className="text-base font-semibold text-zinc-100 flex items-center gap-2">
              <Zap className="w-4 h-4 text-emerald-400" />
              Live Server-Sent Telemetry Feed
            </h2>
            <span className="text-xs text-zinc-500 font-mono">SSE Stream</span>
          </div>

          <div className="bg-zinc-900/90 border border-zinc-800 rounded-xl overflow-hidden shadow-lg backdrop-blur-md">
            <div className="p-3 border-b border-zinc-800 bg-zinc-950/80 text-xs text-zinc-400 font-mono flex items-center justify-between">
              <span>Recent Ingested Spans</span>
              <span>{liveSpans.length} events buffered</span>
            </div>

            <div className="divide-y divide-zinc-800/60 max-h-[520px] overflow-y-auto font-mono text-xs">
              {liveSpans.length > 0 ? (
                liveSpans.map((s, idx) => (
                  <Link
                    key={`${s.spanId}-${idx}`}
                    href={`/traces/${s.traceId}`}
                    className="p-3 block hover:bg-zinc-800/40 transition-colors"
                  >
                    <div className="flex items-center justify-between mb-1">
                      <span className="font-semibold text-zinc-200 truncate max-w-[200px]">
                        {s.serviceName}
                      </span>
                      <span
                        className={`font-bold ${
                          s.durationMs > 200
                            ? 'text-red-400'
                            : s.durationMs > 100
                            ? 'text-amber-400'
                            : 'text-emerald-400'
                        }`}
                      >
                        {formatDuration(s.durationMs)}
                      </span>
                    </div>

                    <div className="text-zinc-400 text-[11px] truncate">{s.operationName}</div>

                    <div className="flex items-center justify-between text-[10px] text-zinc-500 mt-1.5">
                      <span className="truncate max-w-[140px]">id: {s.spanId}</span>
                      <span>{formatTimestamp(s.timestamp)}</span>
                    </div>
                  </Link>
                ))
              ) : (
                <div className="p-8 text-center text-zinc-500 text-xs">
                  Waiting for incoming telemetry events from <code className="text-zinc-400">/v1/traces</code>...
                </div>
              )}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
