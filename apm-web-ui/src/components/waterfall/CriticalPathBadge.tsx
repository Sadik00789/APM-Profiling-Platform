'use client';

import React from 'react';
import { Zap } from 'lucide-react';

interface CriticalPathBadgeProps {
  exclusivePercent?: number;
  compact?: boolean;
}

export const CriticalPathBadge: React.FC<CriticalPathBadgeProps> = ({ exclusivePercent, compact = false }) => {
  if (compact) {
    return (
      <span
        title="Critical Path Bottleneck"
        className="inline-flex items-center justify-center p-0.5 rounded bg-amber-500/20 text-amber-400 border border-amber-500/40"
      >
        <Zap className="w-3 h-3 fill-amber-400 text-amber-400" />
      </span>
    );
  }

  return (
    <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[10px] font-semibold bg-amber-500/15 text-amber-300 border border-amber-500/30">
      <Zap className="w-3 h-3 fill-amber-400 text-amber-400" />
      Critical Path {exclusivePercent != null && exclusivePercent > 0 ? `(${exclusivePercent.toFixed(1)}%)` : ''}
    </span>
  );
};
