'use client';

import React from 'react';
import { LatencyHistogramResponse } from '../../types/profile';
import { formatNumber } from '../../lib/utils/formatters';

interface LatencyHistogramProps {
  histogram: LatencyHistogramResponse;
}

export const LatencyHistogram: React.FC<LatencyHistogramProps> = ({ histogram }) => {
  const maxCount = Math.max(...histogram.buckets.map((b) => b.count), 1);

  return (
    <div className="bg-zinc-900/90 border border-zinc-800 rounded-xl p-5 shadow-lg backdrop-blur-md">
      {/* Header with Percentiles */}
      <div className="flex flex-wrap items-center justify-between gap-3 mb-4">
        <div>
          <h3 className="text-sm font-semibold text-zinc-100 font-sans">
            Latency Distribution ({histogram.serviceName})
          </h3>
          <p className="text-xs text-zinc-400 font-mono mt-0.5">
            P50: <span className="text-emerald-400 font-bold">{histogram.p50Ms}ms</span> • P95:{' '}
            <span className="text-amber-400 font-bold">{histogram.p95Ms}ms</span> • P99:{' '}
            <span className="text-red-400 font-bold">{histogram.p99Ms}ms</span>
          </p>
        </div>
      </div>

      {/* Histogram Bars */}
      <div className="space-y-2">
        {histogram.buckets.map((bucket) => {
          const percent = (bucket.count / maxCount) * 100;
          return (
            <div key={bucket.range} className="flex items-center gap-3 text-xs font-mono">
              <span className="w-20 text-zinc-400 text-right truncate">{bucket.range}</span>
              <div className="flex-1 h-5 bg-zinc-950 rounded-md overflow-hidden p-0.5 border border-zinc-800">
                <div
                  className="h-full rounded bg-gradient-to-r from-amber-500/80 to-orange-500/80 transition-all duration-300 flex items-center px-2"
                  style={{ width: `${Math.max(1, percent)}%` }}
                ></div>
              </div>
              <span className="w-16 text-zinc-300 font-semibold">{formatNumber(bucket.count)}</span>
            </div>
          );
        })}
      </div>
    </div>
  );
};
