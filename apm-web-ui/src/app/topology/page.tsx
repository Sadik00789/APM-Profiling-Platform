'use client';

import React, { useState, useEffect, useCallback } from 'react';
import { Network, Server, Zap, RefreshCw, Activity, ArrowRight } from 'lucide-react';
import { ServiceTopologyResponse } from '../../types/trace';
import { ServiceDependencyMap } from '../../components/topology/ServiceDependencyMap';
import { formatNumber } from '../../lib/utils/formatters';

export default function TopologyPage() {
  const [topologyData, setTopologyData] = useState<ServiceTopologyResponse | null>(null);
  const [isLoading, setIsLoading] = useState(true);

  const fetchTopology = useCallback(async () => {
    setIsLoading(true);
    try {
      const res = await fetch('/api/v1/topology');
      if (res.ok) {
        const data = await res.json();
        setTopologyData(data);
      }
    } catch (e) {
      console.error('Failed to load topology:', e);
    } finally {
      setIsLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchTopology();
  }, [fetchTopology]);

  return (
    <div className="space-y-6">
      {/* Title */}
      <div className="flex flex-wrap items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-zinc-100 tracking-tight flex items-center gap-2">
            <Network className="w-6 h-6 text-emerald-400" />
            Service Dependency Mesh & Topology Map
          </h1>
          <p className="text-xs text-zinc-400 font-mono mt-1">
            Real-time inter-service RPC communication graph, throughput rates, and latency heatmaps.
          </p>
        </div>

        <button
          onClick={fetchTopology}
          className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-zinc-800 border border-zinc-700 text-zinc-300 text-xs font-mono hover:text-zinc-100 transition-colors"
        >
          <RefreshCw className="w-3.5 h-3.5" />
          Refresh Topology
        </button>
      </div>

      {/* Main Force Graph */}
      {isLoading && !topologyData ? (
        <div className="h-[550px] flex items-center justify-center bg-zinc-950/80 rounded-xl border border-zinc-800 text-zinc-400 text-xs font-mono">
          Assembling service mesh topology from ClickHouse traces...
        </div>
      ) : (
        <ServiceDependencyMap
          topologyData={topologyData}
          onRefresh={fetchTopology}
        />
      )}

      {/* Top Communicating Service Edges Table */}
      {topologyData && topologyData.edges.length > 0 && (
        <div className="bg-zinc-900/90 border border-zinc-800 rounded-xl overflow-hidden shadow-xl backdrop-blur-md">
          <div className="p-4 border-b border-zinc-800 bg-zinc-950/80 text-xs font-mono text-zinc-400 flex items-center justify-between">
            <span>Inter-Service RPC Traffic Matrix</span>
            <span>{topologyData.edges.length} Active Dependency Edges</span>
          </div>

          <div className="divide-y divide-zinc-800/40 text-xs font-mono">
            {topologyData.edges.map((edge, idx) => (
              <div key={idx} className="p-3.5 flex items-center justify-between hover:bg-zinc-800/30 transition-colors">
                <div className="flex items-center gap-3">
                  <span className="px-2 py-0.5 rounded bg-zinc-800 border border-zinc-700 text-zinc-200">
                    {edge.source}
                  </span>
                  <ArrowRight className="w-3.5 h-3.5 text-zinc-500" />
                  <span className="px-2 py-0.5 rounded bg-zinc-800 border border-zinc-700 text-zinc-200">
                    {edge.target}
                  </span>
                </div>

                <div className="flex items-center gap-6 text-zinc-400">
                  <span>
                    Throughput: <strong className="text-zinc-200">{edge.rps} RPS</strong>
                  </span>
                  <span>
                    Avg Latency: <strong className="text-amber-400">{edge.avgLatencyMs} ms</strong>
                  </span>
                  <span>
                    Errors: <strong className={edge.errorCount > 0 ? 'text-red-400' : 'text-emerald-400'}>{edge.errorCount}</strong>
                  </span>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
