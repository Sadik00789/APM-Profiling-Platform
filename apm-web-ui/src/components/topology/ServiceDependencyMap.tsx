'use client';

import React, { useRef, useEffect, useState } from 'react';
import * as d3 from 'd3';
import { ServiceTopologyNode, ServiceTopologyEdge, ServiceTopologyResponse } from '../../types/trace';
import { Server, Database, Globe, Activity, Zap, RefreshCw } from 'lucide-react';
import { formatNumber } from '../../lib/utils/formatters';

interface ServiceDependencyMapProps {
  topologyData: ServiceTopologyResponse | null;
  onRefresh?: () => void;
}

export const ServiceDependencyMap: React.FC<ServiceDependencyMapProps> = ({ topologyData, onRefresh }) => {
  const svgRef = useRef<SVGSVGElement>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const [selectedNode, setSelectedNode] = useState<ServiceTopologyNode | null>(null);

  useEffect(() => {
    if (!topologyData || !svgRef.current || !containerRef.current) return;

    const width = containerRef.current.clientWidth || 900;
    const height = 550;

    const svg = d3.select(svgRef.current);
    svg.selectAll('*').remove();

    // Create deep copies of nodes and edges for D3 simulation mutation
    const nodes: (ServiceTopologyNode & d3.SimulationNodeDatum)[] = topologyData.nodes.map((d) => ({ ...d }));
    const edges = topologyData.edges.map((d) => ({ ...d }));

    // Container Group for D3 Zoom
    const g = svg.append('g');

    const zoom = d3.zoom<SVGSVGElement, unknown>()
      .scaleExtent([0.4, 3])
      .on('zoom', (event) => {
        g.attr('transform', event.transform);
      });

    svg.call(zoom);

    // Arrow Marker Definitions
    const defs = svg.append('defs');
    defs.append('marker')
      .attr('id', 'arrowhead')
      .attr('viewBox', '0 -5 10 10')
      .attr('refX', 26)
      .attr('refY', 0)
      .attr('orient', 'auto')
      .attr('markerWidth', 6)
      .attr('markerHeight', 6)
      .attr('xoverflow', 'visible')
      .append('path')
      .attr('d', 'M 0,-5 L 10 ,0 L 0,5')
      .attr('fill', '#71717a');

    // Force Simulation Setup
    const simulation = d3.forceSimulation(nodes)
      .force('link', d3.forceLink(edges).id((d: any) => d.id).distance(160))
      .force('charge', d3.forceManyBody().strength(-600))
      .force('center', d3.forceCenter(width / 2, height / 2))
      .force('collision', d3.forceCollide().radius(60));

    // Render Edges
    const link = g.append('g')
      .selectAll('line')
      .data(edges)
      .enter()
      .append('line')
      .attr('stroke', '#3f3f46')
      .attr('stroke-width', (d) => Math.max(1.5, Math.min(5, Math.log10(d.callCount || 10))))
      .attr('stroke-opacity', 0.8)
      .attr('marker-end', 'url(#arrowhead)');

    // Render Edge Labels (RPS & Latency)
    const linkText = g.append('g')
      .selectAll('text')
      .data(edges)
      .enter()
      .append('text')
      .attr('font-size', '10px')
      .attr('font-family', 'monospace')
      .attr('fill', '#a1a1aa')
      .attr('text-anchor', 'middle')
      .text((d) => `${d.rps} rps • ${d.avgLatencyMs}ms`);

    // Render Nodes
    const node = g.append('g')
      .selectAll('g')
      .data(nodes)
      .enter()
      .append('g')
      .attr('cursor', 'pointer')
      .call(
        d3.drag<SVGGElement, any>()
          .on('start', (event, d) => {
            if (!event.active) simulation.alphaTarget(0.3).restart();
            d.fx = d.x;
            d.fy = d.y;
          })
          .on('drag', (event, d) => {
            d.fx = event.x;
            d.fy = event.y;
          })
          .on('end', (event, d) => {
            if (!event.active) simulation.alphaTarget(0);
            d.fx = null;
            d.fy = null;
          })
      )
      .on('click', (event, d) => {
        setSelectedNode(d);
      });

    // Glowing Status Circle
    node.append('circle')
      .attr('r', 24)
      .attr('fill', '#18181b')
      .attr('stroke', (d) =>
        d.status === 'critical' ? '#ef4444' : d.status === 'degraded' ? '#f59e0b' : '#10b981'
      )
      .attr('stroke-width', 2.5)
      .attr('filter', 'drop-shadow(0 0 8px rgba(0,0,0,0.5))');

    // Inner icon text
    node.append('text')
      .attr('text-anchor', 'middle')
      .attr('dy', '0.35em')
      .attr('fill', '#e4e4e7')
      .attr('font-size', '11px')
      .attr('font-family', 'sans-serif')
      .attr('font-weight', 'bold')
      .text((d) => (d.type === 'GATEWAY' ? 'GW' : d.type === 'DATABASE' ? 'DB' : 'SVC'));

    // Label below node
    node.append('text')
      .attr('text-anchor', 'middle')
      .attr('dy', '36px')
      .attr('fill', '#f4f4f5')
      .attr('font-size', '11px')
      .attr('font-family', 'monospace')
      .attr('font-weight', 'bold')
      .text((d) => d.name);

    // Subtitle metrics (RPS and P95)
    node.append('text')
      .attr('text-anchor', 'middle')
      .attr('dy', '48px')
      .attr('fill', '#71717a')
      .attr('font-size', '9px')
      .attr('font-family', 'monospace')
      .text((d) => `${d.rps} RPS • ${d.p95Ms}ms`);

    // Simulation Tick
    simulation.on('tick', () => {
      link
        .attr('x1', (d: any) => d.source.x)
        .attr('y1', (d: any) => d.source.y)
        .attr('x2', (d: any) => d.target.x)
        .attr('y2', (d: any) => d.target.y);

      linkText
        .attr('x', (d: any) => (d.source.x + d.target.x) / 2)
        .attr('y', (d: any) => (d.source.y + d.target.y) / 2 - 5);

      node.attr('transform', (d: any) => `translate(${d.x},${d.y})`);
    });

    return () => {
      simulation.stop();
    };
  }, [topologyData]);

  return (
    <div className="relative flex flex-col bg-zinc-900/90 border border-zinc-800 rounded-xl overflow-hidden shadow-2xl backdrop-blur-md">
      {/* Top Map Action Bar */}
      <div className="flex items-center justify-between p-4 border-b border-zinc-800 bg-zinc-950/80">
        <div className="flex items-center gap-2">
          <Activity className="w-4 h-4 text-emerald-400" />
          <span className="font-semibold text-sm text-zinc-100">Live Service Dependency Mesh</span>
          <span className="text-xs text-zinc-500 font-mono">({topologyData?.nodes.length || 0} nodes, {topologyData?.edges.length || 0} edges)</span>
        </div>

        <div className="flex items-center gap-3">
          <div className="flex items-center gap-2 text-xs font-mono">
            <span className="flex items-center gap-1 text-emerald-400">
              <span className="w-2 h-2 rounded-full bg-emerald-500"></span> Healthy
            </span>
            <span className="flex items-center gap-1 text-amber-400">
              <span className="w-2 h-2 rounded-full bg-amber-500"></span> Degraded
            </span>
            <span className="flex items-center gap-1 text-red-400">
              <span className="w-2 h-2 rounded-full bg-red-500"></span> Critical
            </span>
          </div>

          {onRefresh && (
            <button
              onClick={onRefresh}
              className="p-1.5 rounded-lg bg-zinc-800 text-zinc-400 hover:text-zinc-100 transition-colors"
            >
              <RefreshCw className="w-4 h-4" />
            </button>
          )}
        </div>
      </div>

      {/* SVG Canvas Container */}
      <div ref={containerRef} className="relative w-full h-[550px] bg-zinc-950/90 cursor-grab active:cursor-grabbing">
        <svg ref={svgRef} className="w-full h-full" />

        {/* Selected Node Overlay Badge */}
        {selectedNode && (
          <div className="absolute bottom-4 left-4 bg-zinc-900/95 border border-zinc-700/80 rounded-xl p-4 shadow-xl text-xs font-mono max-w-xs backdrop-blur-md">
            <div className="flex items-center justify-between font-bold text-amber-400 mb-1">
              <span>{selectedNode.name}</span>
              <span className="uppercase text-[10px] px-1.5 py-0.5 rounded bg-zinc-800 text-zinc-300">
                {selectedNode.type}
              </span>
            </div>
            <div className="space-y-1 text-zinc-300 mt-2">
              <div>Throughput: <span className="text-zinc-100 font-semibold">{selectedNode.rps} req/sec</span></div>
              <div>P95 Latency: <span className="text-zinc-100 font-semibold">{selectedNode.p95Ms} ms</span></div>
              <div>Error Rate: <span className={selectedNode.errorRatePercent > 1 ? 'text-red-400' : 'text-emerald-400'}>{selectedNode.errorRatePercent}%</span></div>
              <div>Health Status: <span className="capitalize font-semibold text-zinc-100">{selectedNode.status}</span></div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
};
