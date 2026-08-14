'use client';

import React from 'react';
import { FlameGraphNode } from '../../types/profile';
import { formatNumber, formatPercent } from '../../lib/utils/formatters';

interface StackFrameTooltipProps {
  node: FlameGraphNode | null;
  x: number;
  y: number;
  visible: boolean;
}

export const StackFrameTooltip: React.FC<StackFrameTooltipProps> = ({ node, x, y, visible }) => {
  if (!visible || !node) return null;

  return (
    <div
      className="fixed z-50 pointer-events-none transition-transform duration-75 ease-out shadow-2xl rounded-xl border border-zinc-700/60 bg-zinc-900/95 backdrop-blur-md px-4 py-3 text-xs text-zinc-100 max-w-md"
      style={{
        left: `${Math.min(window.innerWidth - 320, x + 12)}px`,
        top: `${Math.min(window.innerHeight - 180, y + 12)}px`,
      }}
    >
      <div className="font-mono font-semibold text-amber-400 break-words mb-1 text-sm">
        {node.name}
      </div>

      <div className="text-zinc-400 font-mono text-[11px] mb-2 truncate">
        {node.package || 'default'}
      </div>

      <div className="grid grid-cols-2 gap-x-4 gap-y-1.5 border-t border-zinc-800 pt-2 font-mono">
        <div>
          <span className="text-zinc-400">Total Samples:</span>
          <div className="font-semibold text-zinc-200">
            {formatNumber(node.value)} ({node.totalPercent?.toFixed(2)}%)
          </div>
        </div>

        <div>
          <span className="text-zinc-400">Self Samples:</span>
          <div className="font-semibold text-zinc-200">
            {formatNumber(node.selfValue)} ({node.selfPercent?.toFixed(2)}%)
          </div>
        </div>

        {node.diffPercent != null && node.diffPercent !== 0 && (
          <div className="col-span-2 mt-1 border-t border-zinc-800/80 pt-1 flex items-center justify-between">
            <span className="text-zinc-400">Diff Change:</span>
            <span className={`font-bold ${node.diffPercent > 0 ? 'text-red-400' : 'text-emerald-400'}`}>
              {formatPercent(node.diffPercent)} ({formatNumber(node.diffValue || 0)} samples)
            </span>
          </div>
        )}
      </div>

      <div className="mt-2 text-[10px] text-zinc-400 flex items-center gap-1 font-sans">
        <span className="inline-block w-1.5 h-1.5 rounded-full bg-amber-400/80"></span>
        Click frame to zoom in • Click Reset to zoom out
      </div>
    </div>
  );
};
