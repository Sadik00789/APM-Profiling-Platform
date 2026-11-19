'use client';

import React, { useState, useEffect, useCallback } from 'react';
import { DiffFlameGraphResponse, FlameGraphNode, ProfileType } from '../../types/profile';
import { FlameGraphCanvas } from './FlameGraphCanvas';
import { formatNumber, formatPercent } from '../../lib/utils/formatters';
import { GitCompare, TrendingUp, TrendingDown, Search, ArrowLeft, RotateCcw, AlertCircle } from 'lucide-react';

interface DiffFlameGraphProps {
  service: string;
  profileType: ProfileType;
}

export const DiffFlameGraph: React.FC<DiffFlameGraphProps> = ({ service, profileType }) => {
  const [diffData, setDiffData] = useState<DiffFlameGraphResponse | null>(null);
  const [currentRoot, setCurrentRoot] = useState<FlameGraphNode | null>(null);
  const [zoomStack, setZoomStack] = useState<FlameGraphNode[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [searchQuery, setSearchQuery] = useState('');
  const [matchedFrames, setMatchedFrames] = useState<Set<string>>(new Set());

  const [selectedPreset, setSelectedPreset] = useState<'1H_VS_PREV' | 'INCIDENT_VS_BASELINE' | '24H_DIFF'>('1H_VS_PREV');

  const fetchDiff = useCallback(async () => {
    setIsLoading(true);
    setError(null);
    try {
      const now = Math.floor(Date.now() / 1000);
      let bFrom = now - 7200;
      let bUntil = now - 3600;
      let cFrom = now - 3600;
      let cUntil = now;

      if (selectedPreset === 'INCIDENT_VS_BASELINE') {
        bFrom = now - 1800;
        bUntil = now - 900;
        cFrom = now - 600;
        cUntil = now;
      } else if (selectedPreset === '24H_DIFF') {
        bFrom = now - 86400 * 2;
        bUntil = now - 86400;
        cFrom = now - 86400;
        cUntil = now;
      }

      const params = new URLSearchParams({
        service,
        profileType,
        baselineFrom: String(bFrom),
        baselineUntil: String(bUntil),
        compFrom: String(cFrom),
        compUntil: String(cUntil),
      });

      const res = await fetch(`/api/v1/profiles/diff?${params.toString()}`);
      if (!res.ok) throw new Error(`Failed to load diff: ${res.statusText}`);
      const json: DiffFlameGraphResponse = await res.json();
      setDiffData(json);
      setCurrentRoot(json.root || null);
      setZoomStack([]);
    } catch (err: any) {
      console.error('Diff error:', err);
      setError(err.message || 'Failed to calculate profile diff');
    } finally {
      setIsLoading(false);
    }
  }, [service, profileType, selectedPreset]);

  useEffect(() => {
    fetchDiff();
  }, [fetchDiff]);

  const zoomIntoNode = useCallback(
    (node: FlameGraphNode) => {
      if (node === currentRoot) return;
      setZoomStack((prev) => [...prev, currentRoot || node]);
      setCurrentRoot(node);
    },
    [currentRoot]
  );

  const popZoom = useCallback(() => {
    setZoomStack((prev) => {
      if (prev.length === 0) return prev;
      const next = [...prev];
      const prevRoot = next.pop();
      if (prevRoot) setCurrentRoot(prevRoot);
      return next;
    });
  }, []);

  const resetZoom = useCallback(() => {
    if (diffData?.root) {
      setCurrentRoot(diffData.root);
      setZoomStack([]);
    }
  }, [diffData]);

  // Update search match set
  useEffect(() => {
    if (!searchQuery.trim() || !diffData?.root) {
      setMatchedFrames(new Set());
      return;
    }
    const q = searchQuery.toLowerCase();
    const matches = new Set<string>();

    function walk(node: any) {
      if (node.name.toLowerCase().includes(q) || (node.package && node.package.toLowerCase().includes(q))) {
        matches.add(node.name);
      }
      if (node.children) {
        for (const child of node.children) walk(child);
      }
    }

    walk(diffData.root);
    setMatchedFrames(matches);
  }, [searchQuery, diffData]);

  return (
    <div className="flex flex-col gap-4">
      {/* Time Slice Comparison Picker Drawer */}
      <div className="bg-zinc-900/90 border border-zinc-800 rounded-xl p-4 shadow-lg backdrop-blur-md flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-3">
          <div className="flex items-center gap-1.5 text-zinc-300 font-semibold text-xs font-mono">
            <GitCompare className="w-4 h-4 text-purple-400" />
            <span>Comparison Window:</span>
          </div>

          <div className="flex items-center gap-1.5 text-xs font-mono">
            <button
              onClick={() => setSelectedPreset('1H_VS_PREV')}
              className={`px-3 py-1 rounded-lg border transition-all ${
                selectedPreset === '1H_VS_PREV'
                  ? 'bg-purple-500/20 text-purple-300 border-purple-500/40 font-bold'
                  : 'bg-zinc-800/80 text-zinc-400 border-zinc-700/60 hover:text-zinc-200'
              }`}
            >
              Previous 1h vs Current 1h
            </button>

            <button
              onClick={() => setSelectedPreset('INCIDENT_VS_BASELINE')}
              className={`px-3 py-1 rounded-lg border transition-all ${
                selectedPreset === 'INCIDENT_VS_BASELINE'
                  ? 'bg-purple-500/20 text-purple-300 border-purple-500/40 font-bold'
                  : 'bg-zinc-800/80 text-zinc-400 border-zinc-700/60 hover:text-zinc-200'
              }`}
            >
              Incident Peak vs Baseline
            </button>

            <button
              onClick={() => setSelectedPreset('24H_DIFF')}
              className={`px-3 py-1 rounded-lg border transition-all ${
                selectedPreset === '24H_DIFF'
                  ? 'bg-purple-500/20 text-purple-300 border-purple-500/40 font-bold'
                  : 'bg-zinc-800/80 text-zinc-400 border-zinc-700/60 hover:text-zinc-200'
              }`}
            >
              Yesterday vs Today
            </button>
          </div>
        </div>

        {/* Search within diff */}
        <div className="relative flex items-center">
          <Search className="absolute left-3 w-3.5 h-3.5 text-zinc-500" />
          <input
            type="text"
            placeholder="Search diff stack frames..."
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="bg-zinc-950/80 border border-zinc-700/60 rounded-lg pl-9 pr-3 py-1 text-xs text-zinc-200 placeholder-zinc-500 outline-none focus:border-purple-500 w-56 font-mono"
          />
        </div>
      </div>

      {/* Diff Metrics Header */}
      {diffData && (
        <div className="grid grid-cols-1 md:grid-cols-4 gap-3">
          <div className="bg-zinc-900/90 border border-zinc-800 rounded-xl p-4 shadow-sm">
            <div className="text-xs text-zinc-400 font-medium font-sans">Baseline Samples (A)</div>
            <div className="text-xl font-bold font-mono text-zinc-200 mt-1">
              {formatNumber(diffData.baselineTotal)}
            </div>
            <div className="text-[11px] text-zinc-500 font-mono mt-0.5">Reference period</div>
          </div>

          <div className="bg-zinc-900/90 border border-zinc-800 rounded-xl p-4 shadow-sm">
            <div className="text-xs text-zinc-400 font-medium font-sans">Comparison Samples (B)</div>
            <div className="text-xl font-bold font-mono text-zinc-200 mt-1">
              {formatNumber(diffData.comparisonTotal)}
            </div>
            <div className="text-[11px] text-zinc-500 font-mono mt-0.5">Active / Incident window</div>
          </div>

          <div className="bg-zinc-900/90 border border-zinc-800 rounded-xl p-4 shadow-sm">
            <div className="text-xs text-zinc-400 font-medium font-sans">Net Performance Delta</div>
            <div className={`text-xl font-bold font-mono mt-1 flex items-center gap-1.5 ${
              diffData.overallChangePercent > 0 ? 'text-red-400' : 'text-sky-400'
            }`}>
              {diffData.overallChangePercent > 0 ? <TrendingUp className="w-4 h-4" /> : <TrendingDown className="w-4 h-4" />}
              {formatPercent(diffData.overallChangePercent)}
            </div>
            <div className="text-[11px] text-zinc-500 font-mono mt-0.5">
              {diffData.comparisonTotal - diffData.baselineTotal > 0 ? '+' : ''}
              {formatNumber(diffData.comparisonTotal - diffData.baselineTotal)} samples
            </div>
          </div>

          <div className="bg-zinc-900/90 border border-zinc-800 rounded-xl p-4 shadow-sm flex flex-col justify-between">
            <div className="text-xs text-zinc-400 font-medium font-sans">Differential Heatmap Scale</div>
            <div className="flex items-center gap-3 text-xs font-mono mt-1">
              <span className="flex items-center gap-1.5 text-red-400 font-medium">
                <span className="w-2.5 h-2.5 rounded-sm bg-red-500 shadow-sm"></span> Red (Regression)
              </span>
              <span className="flex items-center gap-1.5 text-sky-400 font-medium">
                <span className="w-2.5 h-2.5 rounded-sm bg-sky-400 shadow-sm"></span> Blue (Optimization)
              </span>
            </div>
          </div>
        </div>
      )}

      {isLoading ? (
        <div className="h-96 flex items-center justify-center bg-zinc-950/60 rounded-xl border border-zinc-800 text-zinc-400 text-xs font-mono">
          Calculating differential call stack profile across time windows...
        </div>
      ) : error ? (
        <div className="p-8 flex items-center justify-center gap-2 bg-red-500/10 border border-red-500/30 rounded-xl text-red-400 text-xs font-mono">
          <AlertCircle className="w-4 h-4 shrink-0" />
          <span>{error}</span>
        </div>
      ) : (currentRoot || diffData?.root) ? (
        <div className="space-y-2">
          {zoomStack.length > 0 && (
            <div className="flex items-center justify-between bg-zinc-900/90 border border-zinc-800 rounded-xl px-4 py-2 text-xs font-mono">
              <div className="flex items-center gap-2 text-zinc-300 truncate">
                <button
                  onClick={popZoom}
                  className="flex items-center gap-1 text-purple-300 hover:text-purple-200 bg-purple-500/15 px-2 py-0.5 rounded border border-purple-500/30"
                >
                  <ArrowLeft className="w-3 h-3" />
                  Back
                </button>
                <span className="text-zinc-500">Zoomed into:</span>
                <span className="font-semibold text-purple-300 truncate max-w-lg">
                  {currentRoot?.name}
                </span>
              </div>
              <button
                onClick={resetZoom}
                className="flex items-center gap-1 text-zinc-400 hover:text-zinc-200 text-xs px-2 py-0.5 rounded bg-zinc-800 border border-zinc-700"
              >
                <RotateCcw className="w-3 h-3" />
                Reset Zoom
              </button>
            </div>
          )}
          <FlameGraphCanvas
            rootNode={currentRoot || diffData!.root}
            searchQuery={searchQuery}
            matchedFrames={matchedFrames}
            onNodeClick={zoomIntoNode}
            isDiffMode={true}
          />
        </div>
      ) : (
        <div className="p-12 text-center bg-zinc-950 rounded-xl border border-zinc-800 text-zinc-500 text-xs font-mono">
          No differential profiling samples available for this period.
        </div>
      )}
    </div>
  );
};
