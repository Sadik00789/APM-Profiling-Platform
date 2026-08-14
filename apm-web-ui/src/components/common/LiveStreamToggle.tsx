'use client';

import React from 'react';
import { Radio, Wifi, WifiOff } from 'lucide-react';

interface LiveStreamToggleProps {
  isConnected: boolean;
  enabled: boolean;
  onToggle: () => void;
  eventCount: number;
}

export const LiveStreamToggle: React.FC<LiveStreamToggleProps> = ({
  isConnected,
  enabled,
  onToggle,
  eventCount,
}) => {
  return (
    <button
      onClick={onToggle}
      className={`flex items-center gap-2 px-3 py-1.5 rounded-lg text-xs font-mono font-medium border transition-all ${
        enabled && isConnected
          ? 'bg-emerald-500/15 text-emerald-400 border-emerald-500/40 shadow-sm'
          : enabled && !isConnected
          ? 'bg-amber-500/15 text-amber-400 border-amber-500/40 animate-pulse'
          : 'bg-zinc-800/80 text-zinc-400 border-zinc-700/60 hover:text-zinc-200'
      }`}
    >
      <span className="relative flex h-2 w-2">
        {enabled && isConnected && (
          <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-emerald-400 opacity-75"></span>
        )}
        <span
          className={`relative inline-flex rounded-full h-2 w-2 ${
            enabled && isConnected ? 'bg-emerald-500' : enabled ? 'bg-amber-500' : 'bg-zinc-600'
          }`}
        ></span>
      </span>

      <span>{enabled && isConnected ? 'LIVE FEED' : enabled ? 'CONNECTING...' : 'LIVE FEED PAUSED'}</span>

      {enabled && isConnected && (
        <span className="px-1.5 py-0.2 rounded bg-emerald-500/20 text-[10px] text-emerald-300">
          {eventCount}
        </span>
      )}
    </button>
  );
};
