'use client';

import { useState, useEffect, useCallback } from 'react';
import { FlameGraphNode, FlameGraphResponse, ProfileType } from '../types/profile';

interface UseFlameGraphProps {
  service: string;
  profileType: ProfileType;
  fromTimestampSec?: number;
  untilTimestampSec?: number;
}

export function useFlameGraphData({ service, profileType, fromTimestampSec, untilTimestampSec }: UseFlameGraphProps) {
  const [data, setData] = useState<FlameGraphResponse | null>(null);
  const [currentRoot, setCurrentRoot] = useState<FlameGraphNode | null>(null);
  const [zoomStack, setZoomStack] = useState<FlameGraphNode[]>([]);
  const [searchQuery, setSearchQuery] = useState('');
  const [matchedFrames, setMatchedFrames] = useState<Set<string>>(new Set());
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const fetchFlameGraph = useCallback(async () => {
    setIsLoading(true);
    setError(null);
    try {
      const params = new URLSearchParams({
        service,
        profileType,
      });
      if (fromTimestampSec) params.append('fromTimestampSec', String(fromTimestampSec));
      if (untilTimestampSec) params.append('untilTimestampSec', String(untilTimestampSec));

      const res = await fetch(`/api/v1/profiles/flamegraph?${params.toString()}`);
      if (!res.ok) throw new Error(`Failed to load profile data: ${res.statusText}`);
      const json: FlameGraphResponse = await res.json();
      setData(json);
      setCurrentRoot(json.root);
      setZoomStack([]);
    } catch (err: any) {
      console.error('Error fetching flamegraph:', err);
      setError(err.message || 'Error loading flame graph');
    } finally {
      setIsLoading(false);
    }
  }, [service, profileType, fromTimestampSec, untilTimestampSec]);

  useEffect(() => {
    fetchFlameGraph();
  }, [fetchFlameGraph]);

  const zoomIntoNode = useCallback((node: FlameGraphNode) => {
    if (node === currentRoot) return;
    setZoomStack((prev) => [...prev, currentRoot || node]);
    setCurrentRoot(node);
  }, [currentRoot]);

  const resetZoom = useCallback(() => {
    if (data?.root) {
      setCurrentRoot(data.root);
      setZoomStack([]);
    }
  }, [data]);

  const popZoom = useCallback(() => {
    setZoomStack((prev) => {
      if (prev.length === 0) return prev;
      const nextStack = [...prev];
      const previousRoot = nextStack.pop();
      if (previousRoot) setCurrentRoot(previousRoot);
      return nextStack;
    });
  }, []);

  // Update search matches
  useEffect(() => {
    if (!searchQuery.trim() || !data?.root) {
      setMatchedFrames(new Set());
      return;
    }

    const q = searchQuery.toLowerCase();
    const matches = new Set<string>();

    function walk(node: FlameGraphNode) {
      if (node.name.toLowerCase().includes(q) || (node.package && node.package.toLowerCase().includes(q))) {
        matches.add(node.name);
      }
      if (node.children) {
        for (const child of node.children) walk(child);
      }
    }

    walk(data.root);
    setMatchedFrames(matches);
  }, [searchQuery, data]);

  return {
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
    refetch: fetchFlameGraph,
  };
}
