'use client';

import React, { useState } from 'react';
import { Flame, Layers, GitCompare, Info } from 'lucide-react';
import { ProfileType } from '../../types/profile';
import { useFlameGraphData } from '../../hooks/useFlameGraphData';
import { FlameGraphHeader } from '../../components/flamegraph/FlameGraphHeader';
import { FlameGraphCanvas } from '../../components/flamegraph/FlameGraphCanvas';
import { DiffFlameGraph } from '../../components/flamegraph/DiffFlameGraph';
import { formatNumber } from '../../lib/utils/formatters';

export default function ContinuousProfilingPage() {
  const [selectedService, setSelectedService] = useState('order-service');
  const [profileType, setProfileType] = useState<ProfileType>('CPU');
  const [isDiffMode, setIsDiffMode] = useState(false);

  const services = ['order-service', 'payment-service', 'api-gateway', 'inventory-service'];

  const {
    data,
    currentRoot,
    zoomStack,
    searchQuery,
    matchedFrames,
    isLoading,
    error,
    setSearchQuery,
    zoomIntoNode,
    resetZoom,
    popZoom,
    refetch,
  } = useFlameGraphData({
    service: selectedService,
    profileType,
  });

  return (
    <div className="space-y-6">
      {/* Page Title & Context */}
      <div className="flex flex-wrap items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-zinc-100 tracking-tight flex items-center gap-2">
            <Flame className="w-6 h-6 text-amber-400 fill-amber-400" />
            Continuous Profiling Flame Graph Explorer
          </h1>
          <p className="text-xs text-zinc-400 font-mono mt-1">
            Off-CPU & on-CPU stack trace aggregation, prefix tree collapsing, and differential analysis.
          </p>
        </div>

        {data && (
          <div className="flex items-center gap-3 text-xs font-mono text-zinc-400">
            <span>
              Total Samples: <strong className="text-zinc-200">{formatNumber(data.totalSamples)}</strong>
            </span>
            <span>•</span>
            <span>
              Tree Depth: <strong className="text-zinc-200">{data.maxDepth}</strong>
            </span>
            <span>•</span>
            <span>
              Nodes: <strong className="text-zinc-200">{data.totalNodes}</strong>
            </span>
          </div>
        )}
      </div>

      {/* Flame Graph Controls Header */}
      <FlameGraphHeader
        services={services}
        selectedService={selectedService}
        onSelectService={setSelectedService}
        profileType={profileType}
        onSelectProfileType={setProfileType}
        searchQuery={searchQuery}
        onSearchChange={setSearchQuery}
        matchedCount={matchedFrames.size}
        zoomStack={zoomStack}
        currentRoot={currentRoot}
        onResetZoom={resetZoom}
        onPopZoom={popZoom}
        onRefresh={refetch}
        isDiffMode={isDiffMode}
        onToggleDiffMode={() => setIsDiffMode(!isDiffMode)}
      />

      {/* Main Viewport */}
      {isDiffMode ? (
        <DiffFlameGraph service={selectedService} profileType={profileType} />
      ) : isLoading ? (
        <div className="h-96 flex items-center justify-center bg-zinc-950/80 rounded-xl border border-zinc-800 text-zinc-400 text-xs font-mono">
          Aggregating continuous call stack samples from ClickHouse...
        </div>
      ) : error ? (
        <div className="p-8 text-center bg-red-500/10 border border-red-500/30 rounded-xl text-red-400 text-xs font-mono">
          {error}
        </div>
      ) : currentRoot ? (
        <div className="space-y-3">
          <FlameGraphCanvas
            rootNode={currentRoot}
            searchQuery={searchQuery}
            matchedFrames={matchedFrames}
            onNodeClick={zoomIntoNode}
            isDiffMode={false}
          />

          <div className="flex items-center justify-between text-[11px] text-zinc-500 font-mono px-2">
            <div className="flex items-center gap-4">
              <span className="flex items-center gap-1.5">
                <span className="w-2.5 h-2.5 rounded-sm bg-[#e8590c]"></span> Application Code (com.apm.*)
              </span>
              <span className="flex items-center gap-1.5">
                <span className="w-2.5 h-2.5 rounded-sm bg-[#0ca678]"></span> Framework & Netty
              </span>
              <span className="flex items-center gap-1.5">
                <span className="w-2.5 h-2.5 rounded-sm bg-[#f59f00]"></span> Core JVM Runtime
              </span>
              <span className="flex items-center gap-1.5">
                <span className="w-2.5 h-2.5 rounded-sm bg-[#7950f2]"></span> ClickHouse & Database
              </span>
            </div>
            <span>Click any stack frame to zoom in • Hover to inspect samples</span>
          </div>
        </div>
      ) : (
        <div className="p-12 text-center bg-zinc-950 rounded-xl border border-zinc-800 text-zinc-500 text-xs font-mono">
          No profile samples available for this service and time range.
        </div>
      )}
    </div>
  );
}
