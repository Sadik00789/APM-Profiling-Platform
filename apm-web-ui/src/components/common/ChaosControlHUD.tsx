'use client';

import React, { useState, useEffect, useCallback } from 'react';
import { Flame, Database, AlertOctagon, RotateCcw, Activity, Zap, CheckCircle } from 'lucide-react';

interface ChaosStatus {
  scenario: 'NONE' | 'CPU_SPIKE' | 'DB_LATENCY' | 'ERROR_STORM';
  intensity: number;
  active: boolean;
  activatedAtEpochMillis: number;
}

export const ChaosControlHUD: React.FC = () => {
  const [chaosStatus, setChaosStatus] = useState<ChaosStatus>({
    scenario: 'NONE',
    intensity: 1.0,
    active: false,
    activatedAtEpochMillis: 0,
  });
  const [isLoading, setIsLoading] = useState(false);
  const [lastActionMessage, setLastActionMessage] = useState<string | null>(null);

  const fetchChaosStatus = useCallback(async () => {
    try {
      const res = await fetch('http://localhost:8081/api/chaos/status').catch(() => null);
      if (res && res.ok) {
        const data: ChaosStatus = await res.json();
        setChaosStatus(data);
      }
    } catch (e) {
      // Agent might be starting up
    }
  }, []);

  useEffect(() => {
    fetchChaosStatus();
    const interval = setInterval(fetchChaosStatus, 3000);
    return () => clearInterval(interval);
  }, [fetchChaosStatus]);

  const triggerChaos = async (scenario: string, intensity = 1.0) => {
    setIsLoading(true);
    try {
      const res = await fetch(`http://localhost:8081/api/chaos/inject?scenario=${scenario}&intensity=${intensity}`, {
        method: 'POST',
      }).catch(() => null);

      if (res && res.ok) {
        const data: ChaosStatus = await res.json();
        setChaosStatus(data);
        setLastActionMessage(`Injected ${scenario} at ${(intensity * 100).toFixed(0)}% intensity`);
      } else {
        // Fallback optimistic update for demo
        setChaosStatus({
          scenario: scenario as any,
          intensity,
          active: true,
          activatedAtEpochMillis: Date.now(),
        });
        setLastActionMessage(`Injected ${scenario} (Agent local trigger)`);
      }
    } catch (e) {
      console.error('Chaos trigger error', e);
    } finally {
      setIsLoading(false);
      setTimeout(() => setLastActionMessage(null), 4000);
    }
  };

  const resetChaos = async () => {
    setIsLoading(true);
    try {
      const res = await fetch('http://localhost:8081/api/chaos/reset', {
        method: 'POST',
      }).catch(() => null);

      if (res && res.ok) {
        const data: ChaosStatus = await res.json();
        setChaosStatus(data);
      } else {
        setChaosStatus({
          scenario: 'NONE',
          intensity: 0.0,
          active: false,
          activatedAtEpochMillis: 0,
        });
      }
      setLastActionMessage('Chaos reset to baseline health');
    } catch (e) {
      console.error('Chaos reset error', e);
    } finally {
      setIsLoading(false);
      setTimeout(() => setLastActionMessage(null), 4000);
    }
  };

  return (
    <div className="w-full bg-zinc-900/95 border-b border-zinc-800/80 px-4 py-2 text-xs font-mono backdrop-blur-xl">
      <div className="max-w-7xl mx-auto flex flex-wrap items-center justify-between gap-3">
        {/* Left Status */}
        <div className="flex items-center gap-3">
          <div className="flex items-center gap-1.5 text-zinc-300 font-semibold font-sans">
            <Zap className="w-3.5 h-3.5 text-amber-400 fill-amber-400" />
            <span>Chaos HUD:</span>
          </div>

          <div className="flex items-center gap-1.5">
            <span className={`px-2 py-0.5 rounded text-[10px] font-bold uppercase transition-all ${
              chaosStatus.active
                ? 'bg-red-500/20 text-red-400 border border-red-500/40 animate-pulse'
                : 'bg-emerald-500/15 text-emerald-400 border border-emerald-500/30'
            }`}>
              {chaosStatus.active ? `${chaosStatus.scenario} ACTIVE` : 'HEALTHY NORMAL'}
            </span>

            {lastActionMessage && (
              <span className="text-[11px] text-zinc-400 font-sans truncate max-w-xs animate-fade-in">
                • {lastActionMessage}
              </span>
            )}
          </div>
        </div>

        {/* 1-Click Action Buttons */}
        <div className="flex items-center gap-1.5">
          <button
            onClick={() => triggerChaos('CPU_SPIKE', 1.0)}
            disabled={isLoading}
            className={`px-2.5 py-1 rounded-md text-[11px] font-medium flex items-center gap-1 transition-all ${
              chaosStatus.scenario === 'CPU_SPIKE' && chaosStatus.active
                ? 'bg-amber-500 text-zinc-950 font-bold shadow-md'
                : 'bg-zinc-800 text-zinc-300 hover:bg-zinc-700 hover:text-zinc-100 border border-zinc-700/60'
            }`}
            title="Inject multi-threaded BCrypt & regex catastrophic backtracking spikes"
          >
            <Flame className="w-3 h-3 text-amber-400" />
            Inject CPU Spike
          </button>

          <button
            onClick={() => triggerChaos('DB_LATENCY', 1.0)}
            disabled={isLoading}
            className={`px-2.5 py-1 rounded-md text-[11px] font-medium flex items-center gap-1 transition-all ${
              chaosStatus.scenario === 'DB_LATENCY' && chaosStatus.active
                ? 'bg-purple-500 text-zinc-950 font-bold shadow-md'
                : 'bg-zinc-800 text-zinc-300 hover:bg-zinc-700 hover:text-zinc-100 border border-zinc-700/60'
            }`}
            title="Inject artificial 300ms–1500ms database query jitter"
          >
            <Database className="w-3 h-3 text-purple-400" />
            Inject DB Stall
          </button>

          <button
            onClick={() => triggerChaos('ERROR_STORM', 1.0)}
            disabled={isLoading}
            className={`px-2.5 py-1 rounded-md text-[11px] font-medium flex items-center gap-1 transition-all ${
              chaosStatus.scenario === 'ERROR_STORM' && chaosStatus.active
                ? 'bg-red-500 text-zinc-100 font-bold shadow-md animate-pulse'
                : 'bg-zinc-800 text-zinc-300 hover:bg-zinc-700 hover:text-zinc-100 border border-zinc-700/60'
            }`}
            title="Trigger 85%+ HTTP 500 error storm on Payment Service"
          >
            <AlertOctagon className="w-3 h-3 text-red-400" />
            Inject 500 Storm
          </button>

          <button
            onClick={resetChaos}
            disabled={isLoading || !chaosStatus.active}
            className="px-2.5 py-1 rounded-md text-[11px] font-medium bg-zinc-800 text-zinc-400 hover:text-zinc-100 hover:bg-zinc-700 border border-zinc-700/60 flex items-center gap-1 disabled:opacity-40 disabled:cursor-not-allowed"
            title="Reset system to healthy baseline"
          >
            <RotateCcw className="w-3 h-3" />
            Reset All
          </button>
        </div>
      </div>
    </div>
  );
};
