'use client';

import React, { useState } from 'react';
import { ChevronRight, ChevronDown, Server, AlertCircle } from 'lucide-react';
import { SpanRecord, TraceTreeResponse } from '../../types/trace';
import { LayoutSpanItem } from '../../lib/workers/trace-tree.worker';
import { formatDuration } from '../../lib/utils/formatters';
import { CriticalPathBadge } from './CriticalPathBadge';
import { SpanDetailDrawer } from './SpanDetailDrawer';

interface TraceWaterfallProps {
  traceData: TraceTreeResponse;
  layoutItems: LayoutSpanItem[];
  collapsedSpans: Set<string>;
  onToggleCollapse: (spanId: string) => void;
}

export const TraceWaterfall: React.FC<TraceWaterfallProps> = ({
  traceData,
  layoutItems,
  collapsedSpans,
  onToggleCollapse,
}) => {
  const [selectedSpan, setSelectedSpan] = useState<SpanRecord | null>(null);

  const totalDurationMs = traceData.totalDurationMs || 100;
  const timeTicks = [0, 0.25, 0.5, 0.75, 1.0].map((ratio) => ({
    label: formatDuration(totalDurationMs * ratio),
    percent: ratio * 100,
  }));

  return (
    <div className="flex flex-col bg-zinc-900/90 border border-zinc-800 rounded-xl shadow-xl overflow-hidden backdrop-blur-md">
      {/* Waterfall Header with Timeline Grid */}
      <div className="grid grid-cols-12 border-b border-zinc-800 bg-zinc-950/80 px-4 py-2.5 text-xs text-zinc-400 font-mono">
        <div className="col-span-5 flex items-center gap-2 font-sans font-semibold text-zinc-300">
          <span>Span Hierarchy & Operations ({layoutItems.length} spans)</span>
        </div>

        <div className="col-span-7 relative h-5">
          {timeTicks.map((tick, i) => (
            <span
              key={i}
              className="absolute text-[10px] text-zinc-500 transform -translate-x-1/2"
              style={{ left: `${tick.percent}%` }}
            >
              {tick.label}
            </span>
          ))}
        </div>
      </div>

      {/* Waterfall Rows */}
      <div className="divide-y divide-zinc-800/50 max-h-[700px] overflow-y-auto">
        {layoutItems.map((item) => {
          const isSelected = selectedSpan?.spanId === item.span.spanId;
          const isError = item.span.statusCode === 'STATUS_CODE_ERROR';
          const isCollapsed = collapsedSpans.has(item.span.spanId);

          return (
            <div
              key={item.span.spanId}
              onClick={() => setSelectedSpan(item.span)}
              className={`grid grid-cols-12 items-center px-4 py-2 text-xs transition-colors cursor-pointer hover:bg-zinc-800/40 ${
                isSelected ? 'bg-amber-500/10 border-l-2 border-amber-400' : ''
              }`}
            >
              {/* Left Column: Span Hierarchy Node */}
              <div
                className="col-span-5 flex items-center gap-1.5 overflow-hidden pr-3"
                style={{ paddingLeft: `${item.depth * 18}px` }}
              >
                {item.hasChildren ? (
                  <button
                    onClick={(e) => {
                      e.stopPropagation();
                      onToggleCollapse(item.span.spanId);
                    }}
                    className="p-0.5 rounded hover:bg-zinc-700 text-zinc-400"
                  >
                    {isCollapsed ? <ChevronRight className="w-3.5 h-3.5" /> : <ChevronDown className="w-3.5 h-3.5" />}
                  </button>
                ) : (
                  <span className="w-4" />
                )}

                <span
                  className="px-1.5 py-0.5 rounded text-[10px] font-mono font-medium truncate max-w-[120px] bg-zinc-800 border border-zinc-700 text-zinc-300"
                  title={item.span.serviceName}
                >
                  {item.span.serviceName}
                </span>

                <span
                  className="font-mono text-zinc-200 truncate flex-1"
                  title={item.span.operationName}
                >
                  {item.span.operationName}
                </span>

                {isError && (
                  <span title="Span Failed">
                    <AlertCircle className="w-3.5 h-3.5 text-red-400 shrink-0" />
                  </span>
                )}

                {item.isCriticalPath && (
                  <CriticalPathBadge exclusivePercent={item.exclusiveTimePercent} compact />
                )}
              </div>

              {/* Right Column: Gantt Execution Bar */}
              <div className="col-span-7 relative h-7 flex items-center">
                {/* Background grid lines */}
                {timeTicks.map((tick, i) => (
                  <div
                    key={i}
                    className="absolute inset-y-0 border-r border-zinc-800/40 pointer-events-none"
                    style={{ left: `${tick.percent}%` }}
                  />
                ))}

                {/* Execution Bar */}
                <div
                  className={`absolute h-4 rounded-md transition-all duration-150 flex items-center px-1.5 shadow-sm group ${
                    isError
                      ? 'bg-red-500/80 border border-red-400'
                      : item.isCriticalPath
                      ? 'bg-gradient-to-r from-amber-500 to-orange-500 border border-amber-400'
                      : 'bg-indigo-600/70 border border-indigo-500/60'
                  }`}
                  style={{
                    left: `${item.leftPercent}%`,
                    width: `${Math.max(0.5, item.widthPercent)}%`,
                  }}
                  title={`${item.span.serviceName}: ${item.span.operationName} (${formatDuration(item.span.durationMs)})`}
                >
                  {item.widthPercent > 6 && (
                    <span className="text-[10px] font-mono font-bold text-white truncate drop-shadow">
                      {formatDuration(item.span.durationMs)}
                    </span>
                  )}
                </div>

                {/* Duration Label outside if bar is narrow */}
                {item.widthPercent <= 6 && (
                  <span
                    className="absolute text-[10px] font-mono text-zinc-400 ml-1.5"
                    style={{ left: `${item.leftPercent + item.widthPercent}%` }}
                  >
                    {formatDuration(item.span.durationMs)}
                  </span>
                )}
              </div>
            </div>
          );
        })}
      </div>

      {/* Selected Span Detail Drawer */}
      <SpanDetailDrawer
        span={selectedSpan}
        onClose={() => setSelectedSpan(null)}
      />
    </div>
  );
};
