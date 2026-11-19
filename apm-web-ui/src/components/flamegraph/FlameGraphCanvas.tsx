'use client';

import React, { useRef, useEffect, useState, useCallback, useMemo } from 'react';
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
  searchQuery: _searchQuery,
  matchedFrames,
  onNodeClick,
  isDiffMode = false,
}) => {
  const containerRef = useRef<HTMLDivElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);

  const [containerWidth, setContainerWidth] = useState(900);
  const [hoveredNode, setHoveredNode] = useState<FlameGraphNode | null>(null);
  const [mousePos, setMousePos] = useState({ x: 0, y: 0 });
  const renderBoxesRef = useRef<RenderBox[]>([]);

  const ROW_HEIGHT = 20;
  const ROW_GAP = 1;

  useEffect(() => {
    const el = containerRef.current;
    if (!el) return;

    const updateWidth = () => {
      if (el.clientWidth > 0) {
        setContainerWidth(el.clientWidth);
      }
    };
    updateWidth();

    if (typeof ResizeObserver !== 'undefined') {
      const observer = new ResizeObserver(updateWidth);
      observer.observe(el);
      return () => observer.disconnect();
    }

    window.addEventListener('resize', updateWidth);
    return () => window.removeEventListener('resize', updateWidth);
  }, []);

  const layoutData = useMemo(() => {
    if (!rootNode) {
      return { boxes: [] as RenderBox[], height: 400 };
    }

    const hierarchy = d3
      .hierarchy<FlameGraphNode>(rootNode, (d) => d.children)
      .sum((d) => {
        const hasChildren = Boolean(d.children && d.children.length > 0);
        if (hasChildren) {
          return d.selfValue || 0;
        }
        if (d.value > 0) {
          return d.value;
        }
        if (isDiffMode && d.diffValue != null && d.diffValue < 0) {
          return Math.abs(d.diffValue);
        }
        return d.selfValue || 1;
      })
      .sort((a, b) => (b.value || 0) - (a.value || 0));

    const maxDepth = hierarchy.height;
    const height = Math.max(400, (maxDepth + 2) * (ROW_HEIGHT + ROW_GAP) + 40);

    const partition = d3.partition<FlameGraphNode>().size([containerWidth, height]);
    const partitionRoot = partition(hierarchy);
    const boxes: RenderBox[] = [];

    partitionRoot.each((d) => {
      const x = d.x0;
      const y = height - (d.depth + 1) * (ROW_HEIGHT + ROW_GAP) - 10;
      const w = Math.max(1, d.x1 - d.x0 - 0.5);
      const h = ROW_HEIGHT;
      boxes.push({ node: d.data, x, y, w, h });
    });

    return { boxes, height };
  }, [rootNode, containerWidth, isDiffMode]);

  const renderCanvas = useCallback(() => {
    const canvas = canvasRef.current;
    if (!canvas || !rootNode) return;

    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    const width = containerWidth;
    const height = layoutData.height;
    const dpr = typeof window !== 'undefined' ? window.devicePixelRatio || 1 : 1;

    canvas.width = width * dpr;
    canvas.height = height * dpr;
    canvas.style.width = `${width}px`;
    canvas.style.height = `${height}px`;

    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, width, height);

    ctx.font = '11px "JetBrains Mono", Menlo, monospace';
    ctx.textBaseline = 'middle';

    for (const box of layoutData.boxes) {
      const { node, x, y, w, h } = box;
      const isMatch = matchedFrames.has(node.name);
      const isHovered = hoveredNode === node;

      const fillColor = getFrameColor(
        node.name,
        node.package,
        isMatch,
        isDiffMode ? node.diffPercent : undefined
      );
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

      if (w > 32) {
        ctx.save();
        ctx.beginPath();
        ctx.rect(x + 2, y, w - 4, h);
        ctx.clip();

        ctx.fillStyle = isMatch ? '#18181b' : '#ffffff';
        const pctLabel = node.totalPercent != null ? node.totalPercent.toFixed(1) : '0.0';
        const label = `${node.name} (${pctLabel}%)`;
        ctx.fillText(label, x + 4, y + h / 2 + 0.5);
        ctx.restore();
      }
    }

    renderBoxesRef.current = layoutData.boxes;
  }, [rootNode, containerWidth, layoutData, matchedFrames, hoveredNode, isDiffMode]);

  useEffect(() => {
    renderCanvas();
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
