'use client';

import React, { use } from 'react';
import Link from 'next/link';
import { ArrowLeft, Clock, GitGraph, Layers, Zap, AlertCircle, RefreshCw } from 'lucide-react';
import { useTraceWaterfall } from '../../../hooks/useTraceWaterfall';
import { TraceWaterfall } from '../../../components/waterfall/TraceWaterfall';
import { formatDuration } from '../../../lib/utils/formatters';

interface TraceDetailsPageProps {
  params: Promise<{ traceId: string }>;
}

export default function TraceDetailsPage({ params }: TraceDetailsPageProps) {
  const resolvedParams = use(params);
  const traceId = resolvedParams.traceId;

  const {
    traceData,
    layoutItems,
    collapsedSpans,
    isLoading,
    error,
    toggleCollapse,
    refetch,
  } = useTraceWaterfall(traceId);

  return (
    <div className="space-y-6">
      {/* Top Navigation & Action Header */}
      <div className="flex flex-wrap items-center justify-between gap-4">
        <div className="flex items-center gap-3">
          <Link
            href="/traces"
            className="p-2 rounded-lg bg-zinc-900 border border-zinc-800 text-zinc-400 hover:text-zinc-100 hover:bg-zinc-800 transition-colors"
          >
            <ArrowLeft className="w-4 h-4" />
          </Link>
          <div>
            <h1 className="text-xl font-bold text-zinc-100 tracking-tight flex items-center gap-2 font-mono">
              <GitGraph className="w-5 h-5 text-indigo-400" />
              Trace: <span className="text-indigo-400 font-semibold">{traceId}</span>
            </h1>
            <p className="text-xs text-zinc-500 font-mono mt-0.5">
              Asynchronous DAG reconstruction and critical path bottleneck identification.
            </p>
          </div>
        </div>

        <button
          onClick={refetch}
          className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-zinc-800 border border-zinc-700 text-zinc-300 text-xs font-mono hover:text-zinc-100 transition-colors"
        >
          <RefreshCw className="w-3.5 h-3.5" />
          Refresh
        </button>
      </div>

      {/* Trace Metrics Summary Bar */}
      {traceData && (
        <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
          <div className="bg-zinc-900/90 border border-zinc-800 rounded-xl p-4 shadow-sm backdrop-blur-md">
            <div className="text-xs text-zinc-400 font-medium flex items-center gap-1.5">
              <Clock className="w-3.5 h-3.5 text-amber-400" />
              Total Latency
            </div>
            <div className="text-xl font-bold font-mono text-zinc-100 mt-1">
              {formatDuration(traceData.totalDurationMs)}
            </div>
          </div>

          <div className="bg-zinc-900/90 border border-zinc-800 rounded-xl p-4 shadow-sm backdrop-blur-md">
            <div className="text-xs text-zinc-400 font-medium flex items-center gap-1.5">
              <Layers className="w-3.5 h-3.5 text-indigo-400" />
              Total Spans
            </div>
            <div className="text-xl font-bold font-mono text-zinc-100 mt-1">
              {traceData.totalSpans}
            </div>
          </div>

          <div className="bg-zinc-900/90 border border-zinc-800 rounded-xl p-4 shadow-sm backdrop-blur-md">
            <div className="text-xs text-zinc-400 font-medium flex items-center gap-1.5">
              <Zap className="w-3.5 h-3.5 text-amber-400 fill-amber-400" />
              Critical Path Spans
            </div>
            <div className="text-xl font-bold font-mono text-amber-400 mt-1">
              {traceData.criticalPathSpans}
            </div>
          </div>

          <div className="bg-zinc-900/90 border border-zinc-800 rounded-xl p-4 shadow-sm backdrop-blur-md">
            <div className="text-xs text-zinc-400 font-medium flex items-center gap-1.5">
              <AlertCircle className="w-3.5 h-3.5 text-red-400" />
              Errors
            </div>
            <div className={`text-xl font-bold font-mono mt-1 ${traceData.errorCount > 0 ? 'text-red-400' : 'text-emerald-400'}`}>
              {traceData.errorCount}
            </div>
          </div>
        </div>
      )}

      {/* Services Involved Badges */}
      {traceData?.servicesInvolved && traceData.servicesInvolved.length > 0 && (
        <div className="flex items-center gap-2 text-xs font-mono text-zinc-400">
          <span>Services in trace:</span>
          {traceData.servicesInvolved.map((svc) => (
            <span
              key={svc}
              className="px-2 py-0.5 rounded bg-zinc-800/80 border border-zinc-700 text-zinc-300"
            >
              {svc}
            </span>
          ))}
        </div>
      )}

      {/* Main Waterfall Chart View */}
      {isLoading ? (
        <div className="h-96 flex items-center justify-center bg-zinc-950/80 rounded-xl border border-zinc-800 text-zinc-400 text-xs font-mono">
          Reconstructing asynchronous trace DAG and calculating critical path...
        </div>
      ) : error ? (
        <div className="p-8 text-center bg-red-500/10 border border-red-500/30 rounded-xl text-red-400 text-xs font-mono">
          {error}
        </div>
      ) : traceData && layoutItems.length > 0 ? (
        <TraceWaterfall
          traceData={traceData}
          layoutItems={layoutItems}
          collapsedSpans={collapsedSpans}
          onToggleCollapse={toggleCollapse}
        />
      ) : (
        <div className="p-12 text-center bg-zinc-950 rounded-xl border border-zinc-800 text-zinc-500 text-xs font-mono">
          No spans recorded for this trace ID.
        </div>
      )}
    </div>
  );
}
