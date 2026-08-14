'use client';

import React, { useRef, useEffect, useState, useCallback } from 'react';
import * as d3 from 'd3';
import { FlameGraphNode } from '../../types/profile';
import { getFrameColor } from '../../lib/utils/color-generator';
import { StackFrameTooltip } from './StackFrameTooltip';

interface FlameGraphCanvasProps {
  rootNode: FlameGraphNode | null;
  searchQuery: string;
  matchedFrames: Set<string>;
  onNodeClick: (node: FlameGraphNode) => void;
  isDiffMode?: boolean;
}

interface RenderBox {
  node: FlameGraphNode;
  x: number;
  y: number;
  w: number;
  h: number;
}

export const FlameGraphCanvas: React.FC<FlameGraphCanvasProps> = ({
  rootNode,
  searchQuery,
  matchedFrames,
  onNodeClick,
  isDiffMode = false,
}) => {
  const containerRef = useRef<HTMLDivElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);

  const [hoveredNode, setHoveredNode] = useState<FlameGraphNode | null>(null);
  const [mousePos, setMousePos] = useState({ x: 0, y: 0 });
  const renderBoxesRef = useRef<RenderBox[]>([]);

  const ROW_HEIGHT = 20;
  const ROW_GAP = 1;

  const renderCanvas = useCallback(() => {
    const canvas = canvasRef.current;
    const container = containerRef.current;
    if (!canvas || !container || !rootNode) return;

    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    const width = container.clientWidth || 900;
    const dpr = window.devicePixelRatio || 1;

    // Build D3 hierarchy
    const hierarchy = d3.hierarchy<FlameGraphNode>(rootNode, (d) => d.children)
      .sum((d) => (d.children && d.children.length > 0 ? 0 : d.value || 1))
      .sort((a, b) => (b.value || 0) - (a.value || 0));

    const maxDepth = hierarchy.height;
    const height = Math.max(400, (maxDepth + 2) * (ROW_HEIGHT + ROW_GAP) + 40);

    canvas.width = width * dpr;
    canvas.height = height * dpr;
    canvas.style.width = `${width}px`;
    canvas.style.height = `${height}px`;

    ctx.scale(dpr, dpr);
    ctx.clearRect(0, 0, width, height);

    // D3 Partition Layout
    const partition = d3.partition<FlameGraphNode>()
      .size([width, height]);

    const partitionRoot = partition(hierarchy);
    const boxes: RenderBox[] = [];

    ctx.font = '11px "JetBrains Mono", Menlo, monospace';
    ctx.textBaseline = 'middle';

    partitionRoot.each((d) => {
      // Inverted flame graph (root at bottom, call stack going up) or standard (root at top)
      // Flame graphs traditionally have root at top (icicle style) or root at bottom
      const x = d.x0;
      const y = height - (d.depth + 1) * (ROW_HEIGHT + ROW_GAP) - 10; // bottom-up flame graph
      const w = Math.max(1, d.x1 - d.x0 - 0.5);
      const h = ROW_HEIGHT;

      boxes.push({ node: d.data, x, y, w, h });

      const isMatch = matchedFrames.has(d.data.name);
      const isHovered = hoveredNode === d.data;

      // Frame Color
      const fillColor = getFrameColor(d.data.name, d.data.package, isMatch, isDiffMode ? d.data.diffPercent : undefined);
      ctx.fillStyle = fillColor;
      ctx.fillRect(x, y, w, h);

      if (isHovered) {
        ctx.strokeStyle = '#ffffff';
        ctx.lineWidth = 1.5;
        ctx.strokeRect(x, y, w, h);
      } else if (isMatch) {
        ctx.strokeStyle = '#fbbf24';
        ctx.lineWidth = 1.5;
        ctx.strokeRect(x, y, w, h);
      } else {
        ctx.strokeStyle = 'rgba(0, 0, 0, 0.25)';
        ctx.lineWidth = 0.5;
        ctx.strokeRect(x, y, w, h);
      }

      // Render Text Label if width permits
      if (w > 32) {
        ctx.save();
        ctx.beginPath();
        ctx.rect(x + 2, y, w - 4, h);
        ctx.clip();

        ctx.fillStyle = isMatch ? '#18181b' : '#ffffff';
        const label = `${d.data.name} (${d.data.totalPercent ? d.data.totalPercent.toFixed(1) : '100'}%)`;
        ctx.fillText(label, x + 4, y + h / 2 + 0.5);
        ctx.restore();
      }
    });

    renderBoxesRef.current = boxes;
  }, [rootNode, matchedFrames, hoveredNode, isDiffMode]);

  useEffect(() => {
    renderCanvas();
    const handleResize = () => renderCanvas();
    window.addEventListener('resize', handleResize);
    return () => window.removeEventListener('resize', handleResize);
  }, [renderCanvas]);

  const handleMouseMove = (e: React.MouseEvent<HTMLCanvasElement>) => {
    const canvas = canvasRef.current;
    if (!canvas) return;

    const rect = canvas.getBoundingClientRect();
    const mx = e.clientX - rect.left;
    const my = e.clientY - rect.top;

    setMousePos({ x: e.clientX, y: e.clientY });

    const hit = renderBoxesRef.current.find(
      (b) => mx >= b.x && mx <= b.x + b.w && my >= b.y && my <= b.y + b.h
    );

    setHoveredNode(hit ? hit.node : null);
  };

  const handleMouseLeave = () => {
    setHoveredNode(null);
  };

  const handleClick = (e: React.MouseEvent<HTMLCanvasElement>) => {
    const canvas = canvasRef.current;
    if (!canvas) return;

    const rect = canvas.getBoundingClientRect();
    const mx = e.clientX - rect.left;
    const my = e.clientY - rect.top;

    const hit = renderBoxesRef.current.find(
      (b) => mx >= b.x && mx <= b.x + b.w && my >= b.y && my <= b.y + b.h
    );

    if (hit && hit.node) {
      onNodeClick(hit.node);
    }
  };

  return (
    <div ref={containerRef} className="relative w-full overflow-hidden rounded-xl border border-zinc-800 bg-zinc-950 p-2 shadow-inner">
      <canvas
        ref={canvasRef}
        onMouseMove={handleMouseMove}
        onMouseLeave={handleMouseLeave}
        onClick={handleClick}
        className="cursor-pointer block w-full"
      />

      <StackFrameTooltip
        node={hoveredNode}
        x={mousePos.x}
        y={mousePos.y}
        visible={Boolean(hoveredNode)}
      />
    </div>
  );
};
