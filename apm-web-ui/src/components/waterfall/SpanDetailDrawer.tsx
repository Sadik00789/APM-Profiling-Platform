'use client';

import React from 'react';
import Link from 'next/link';
import { X, Flame, Clock, Database, Globe, Server, Hash, ShieldCheck, AlertTriangle, SearchCode } from 'lucide-react';
import { SpanRecord } from '../../types/trace';
import { formatDuration, formatTimestamp } from '../../lib/utils/formatters';
import { CriticalPathBadge } from './CriticalPathBadge';

interface SpanDetailDrawerProps {
  span: SpanRecord | null;
  onClose: () => void;
}

export const SpanDetailDrawer: React.FC<SpanDetailDrawerProps> = ({ span, onClose }) => {
  if (!span) return null;

  const isError = span.statusCode === 'STATUS_CODE_ERROR' || (span.statusCode as string) === 'ERROR';
  
  // Calculate precise execution window (in seconds) for profiling pre-filtering
  const startNano = span.startTimeUnixNano || Date.now() * 1_000_000;
  const durationNano = span.durationNano || (span.durationMs * 1_000_000) || 10_000_000;
  const startSec = Math.floor(startNano / 1_000_000_000);
  const endSec = Math.ceil((startNano + durationNano) / 1_000_000_000);

  const profilingUrl = `/profiling?service=${encodeURIComponent(span.serviceName)}&from=${startSec - 60}&to=${endSec + 60}`;

  return (
    <div className="fixed inset-y-0 right-0 w-full max-w-lg bg-zinc-900/95 backdrop-blur-xl border-l border-zinc-800 shadow-2xl z-50 flex flex-col transition-transform duration-200 ease-out">
      {/* Header */}
      <div className="p-5 border-b border-zinc-800 flex items-start justify-between">
        <div className="flex-1 pr-4">
          <div className="flex items-center gap-2 mb-1.5">
            <span className={`px-2 py-0.5 rounded text-[10px] font-mono font-bold uppercase ${
              isError ? 'bg-red-500/20 text-red-400 border border-red-500/30' : 'bg-emerald-500/20 text-emerald-400 border border-emerald-500/30'
            }`}>
              {span.statusCode.replace('STATUS_CODE_', '')}
            </span>
            <span className="text-xs text-zinc-400 font-mono flex items-center gap-1">
              <Server className="w-3 h-3 text-zinc-500" />
              {span.serviceName}
            </span>
          </div>
          <h2 className="text-base font-semibold text-zinc-100 font-mono break-words">
            {span.operationName}
          </h2>
        </div>

        <button
          onClick={onClose}
          className="p-1 rounded-lg text-zinc-400 hover:text-zinc-100 hover:bg-zinc-800 transition-colors"
        >
          <X className="w-5 h-5" />
        </button>
      </div>

      {/* Body Content */}
      <div className="flex-1 overflow-y-auto p-5 space-y-6 text-xs font-sans">
        {/* Direct Action Button: 1-Click Code Profile Navigation */}
        <div className="bg-gradient-to-r from-amber-500/15 via-orange-500/10 to-purple-500/10 border border-amber-500/30 rounded-xl p-4 shadow-md">
          <div className="flex items-center justify-between mb-1.5">
            <span className="font-semibold text-amber-300 flex items-center gap-1.5 text-xs">
              <Flame className="w-4 h-4 text-amber-400 fill-amber-400" />
              Stack Profile Direct Correlation
            </span>
            <span className="text-[10px] text-amber-400/80 font-mono">Microsecond Window</span>
          </div>
          <p className="text-zinc-400 text-xs mb-3">
            Drill down into method-level CPU & memory allocations captured for <span className="text-zinc-200 font-mono font-semibold">{span.serviceName}</span> during this span execution.
          </p>
          <Link
            href={profilingUrl}
            className="inline-flex items-center justify-center gap-2 w-full py-2.5 px-3 rounded-lg bg-amber-500 text-zinc-950 font-bold text-xs hover:bg-amber-400 transition-all shadow-lg hover:shadow-amber-500/20"
          >
            <SearchCode className="w-4 h-4" />
            🔍 View Stack Profile for This Span Window
          </Link>
        </div>

        {/* Core Metrics Grid */}
        <div className="grid grid-cols-2 gap-3">
          <div className="bg-zinc-950/80 border border-zinc-800/80 rounded-xl p-3">
            <div className="text-zinc-400 font-medium flex items-center gap-1.5 mb-1 font-sans">
              <Clock className="w-3.5 h-3.5 text-amber-400" />
              Duration
            </div>
            <div className="text-lg font-bold font-mono text-zinc-100">
              {formatDuration(span.durationMs)}
            </div>
            <div className="text-[11px] font-mono text-zinc-500 mt-0.5">
              {(span.durationNano || span.durationMs * 1_000_000).toLocaleString()} ns
            </div>
          </div>

          <div className="bg-zinc-950/80 border border-zinc-800/80 rounded-xl p-3">
            <div className="text-zinc-400 font-medium flex items-center gap-1.5 mb-1 font-sans">
              <Hash className="w-3.5 h-3.5 text-indigo-400" />
              Span ID
            </div>
            <div className="font-mono text-xs text-zinc-200 truncate" title={span.spanId}>
              {span.spanId}
            </div>
            <div className="text-[11px] font-mono text-zinc-500 mt-0.5 truncate" title={span.parentSpanId || 'root'}>
              Parent: {span.parentSpanId || 'root'}
            </div>
          </div>
        </div>

        {/* Attributes & Tags */}
        <div>
          <h3 className="text-xs font-semibold text-zinc-300 uppercase tracking-wider mb-2.5 font-sans">
            Span Attributes & Metadata
          </h3>

          {span.attributes && Object.keys(span.attributes).length > 0 ? (
            <div className="rounded-xl border border-zinc-800 bg-zinc-950/80 overflow-hidden divide-y divide-zinc-800/80 font-mono">
              {Object.entries(span.attributes).map(([key, val]) => (
                <div key={key} className="p-2.5 flex flex-col gap-1 hover:bg-zinc-900/40">
                  <span className="text-[11px] text-zinc-400 font-semibold">{key}</span>
                  <span className="text-zinc-200 text-xs break-all bg-zinc-900/60 p-1 rounded border border-zinc-800/60">
                    {val}
                  </span>
                </div>
              ))}
            </div>
          ) : (
            <div className="p-4 rounded-xl border border-zinc-800 bg-zinc-950 text-zinc-500 text-center font-mono">
              No additional attributes attached.
            </div>
          )}
        </div>
      </div>
    </div>
  );
};
