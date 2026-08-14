'use client';

import React from 'react';
import { Search, RotateCcw, Flame, Layers, ArrowLeft, GitCompare, RefreshCw } from 'lucide-react';
import { FlameGraphNode, ProfileType } from '../../types/profile';

interface FlameGraphHeaderProps {
  services: string[];
  selectedService: string;
  onSelectService: (service: string) => void;
  profileType: ProfileType;
  onSelectProfileType: (type: ProfileType) => void;
  searchQuery: string;
  onSearchChange: (query: string) => void;
  matchedCount: number;
  zoomStack: FlameGraphNode[];
  currentRoot: FlameGraphNode | null;
  onResetZoom: () => void;
  onPopZoom: () => void;
  onRefresh: () => void;
  isDiffMode: boolean;
  onToggleDiffMode: () => void;
}

export const FlameGraphHeader: React.FC<FlameGraphHeaderProps> = ({
  services,
  selectedService,
  onSelectService,
  profileType,
  onSelectProfileType,
  searchQuery,
  onSearchChange,
  matchedCount,
  zoomStack,
  currentRoot,
  onResetZoom,
  onPopZoom,
  onRefresh,
  isDiffMode,
  onToggleDiffMode,
}) => {
  return (
    <div className="flex flex-col gap-3 bg-zinc-900/90 border border-zinc-800 rounded-xl p-4 shadow-lg backdrop-blur-md">
      {/* Top Controls Row */}
      <div className="flex flex-wrap items-center justify-between gap-3">
        {/* Service & Profile Type Selectors */}
        <div className="flex items-center gap-2">
          <div className="flex items-center gap-1.5 bg-zinc-800/80 px-3 py-1.5 rounded-lg border border-zinc-700/60">
            <Layers className="w-4 h-4 text-amber-400" />
            <select
              value={selectedService}
              onChange={(e) => onSelectService(e.target.value)}
              className="bg-transparent text-sm font-medium text-zinc-200 outline-none cursor-pointer"
            >
              {services.map((s) => (
                <option key={s} value={s} className="bg-zinc-900 text-zinc-200">
                  {s}
                </option>
              ))}
            </select>
          </div>

          <div className="flex items-center gap-1 bg-zinc-800/80 p-1 rounded-lg border border-zinc-700/60 text-xs">
            {(['CPU', 'ALLOC_SPACE', 'LOCK_TIME'] as ProfileType[]).map((type) => (
              <button
                key={type}
                onClick={() => onSelectProfileType(type)}
                className={`px-2.5 py-1 rounded-md font-medium transition-all ${
                  profileType === type
                    ? 'bg-amber-500/20 text-amber-300 border border-amber-500/40 shadow-sm'
                    : 'text-zinc-400 hover:text-zinc-200'
                }`}
              >
                {type === 'ALLOC_SPACE' ? 'Memory' : type === 'LOCK_TIME' ? 'Locks' : 'CPU'}
              </button>
            ))}
          </div>

          <button
            onClick={onToggleDiffMode}
            className={`flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold border transition-all ${
              isDiffMode
                ? 'bg-purple-600/30 text-purple-300 border-purple-500 shadow-sm'
                : 'bg-zinc-800/80 text-zinc-400 border-zinc-700/60 hover:text-zinc-200'
            }`}
          >
            <GitCompare className="w-3.5 h-3.5" />
            Diff Mode
          </button>
        </div>

        {/* Search and Action Buttons */}
        <div className="flex items-center gap-2">
          <div className="relative flex items-center">
            <Search className="absolute left-3 w-3.5 h-3.5 text-zinc-500" />
            <input
              type="text"
              placeholder="Search stack frame (e.g. BCrypt, handleRequest)..."
              value={searchQuery}
              onChange={(e) => onSearchChange(e.target.value)}
              className="bg-zinc-950/80 border border-zinc-700/60 rounded-lg pl-9 pr-8 py-1.5 text-xs text-zinc-200 placeholder-zinc-500 outline-none focus:border-amber-500/80 w-64 transition-all"
            />
            {searchQuery && (
              <span className="absolute right-2.5 text-[10px] font-mono bg-amber-500/20 text-amber-300 px-1.5 py-0.5 rounded">
                {matchedCount}
              </span>
            )}
          </div>

          <button
            onClick={onRefresh}
            title="Refresh Flame Graph"
            className="p-1.5 rounded-lg bg-zinc-800/80 border border-zinc-700/60 text-zinc-400 hover:text-zinc-100 transition-colors"
          >
            <RefreshCw className="w-4 h-4" />
          </button>
        </div>
      </div>

      {/* Zoom Breadcrumb Bar */}
      {zoomStack.length > 0 && (
        <div className="flex items-center justify-between border-t border-zinc-800/80 pt-2.5 text-xs">
          <div className="flex items-center gap-2 font-mono text-zinc-300 truncate">
            <button
              onClick={onPopZoom}
              className="flex items-center gap-1 text-amber-400 hover:text-amber-300 bg-amber-500/10 px-2 py-0.5 rounded border border-amber-500/30"
            >
              <ArrowLeft className="w-3 h-3" />
              Back
            </button>
            <span className="text-zinc-500">Zoomed into:</span>
            <span className="font-semibold text-amber-300 truncate max-w-lg">
              {currentRoot?.name}
            </span>
          </div>

          <button
            onClick={onResetZoom}
            className="flex items-center gap-1 text-zinc-400 hover:text-zinc-200 text-xs px-2 py-0.5 rounded bg-zinc-800 border border-zinc-700"
          >
            <RotateCcw className="w-3 h-3" />
            Reset Zoom
          </button>
        </div>
      )}
    </div>
  );
};
