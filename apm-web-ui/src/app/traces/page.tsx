'use client';

import React, { useState, useEffect, useCallback } from 'react';
import Link from 'next/link';
import { Search, Filter, GitGraph, ArrowRight, RefreshCw, AlertCircle, CheckCircle2 } from 'lucide-react';
import { SpanRecord } from '../../types/trace';
import { formatDuration, formatTimestamp } from '../../lib/utils/formatters';

export default function TracesSearchPage() {
  const [spans, setSpans] = useState<SpanRecord[]>([]);
  const [selectedService, setSelectedService] = useState('all');
  const [selectedStatus, setSelectedStatus] = useState('all');
  const [minDurationMs, setMinDurationMs] = useState<number>(0);
  const [searchOperation, setSearchOperation] = useState('');
  const [isLoading, setIsLoading] = useState(true);

  const services = ['all', 'api-gateway', 'order-service', 'payment-service', 'inventory-service', 'notification-service'];

  const fetchSpans = useCallback(async () => {
    setIsLoading(true);
    try {
      const params = new URLSearchParams();
      if (selectedService !== 'all') params.append('service', selectedService);
      if (selectedStatus !== 'all') params.append('status', selectedStatus);
      if (minDurationMs > 0) params.append('minDurationMs', String(minDurationMs));
      if (searchOperation.trim()) params.append('operation', searchOperation.trim());
      params.append('limit', '50');

      const res = await fetch(`/api/v1/traces?${params.toString()}`);
      if (res.ok) {
        const data = await res.json();
        setSpans(data.spans || []);
      }
    } catch (e) {
      console.error('Failed to query spans:', e);
    } finally {
      setIsLoading(false);
    }
  }, [selectedService, selectedStatus, minDurationMs, searchOperation]);

  useEffect(() => {
    fetchSpans();
  }, [fetchSpans]);

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex flex-wrap items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-zinc-100 tracking-tight flex items-center gap-2">
            <GitGraph className="w-6 h-6 text-indigo-400" />
            Distributed Trace Explorer
          </h1>
          <p className="text-xs text-zinc-400 font-mono mt-1">
            Search, filter, and inspect distributed transactions across microservices.
          </p>
        </div>

        <button
          onClick={fetchSpans}
          className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-zinc-800 border border-zinc-700 text-zinc-300 text-xs font-mono hover:text-zinc-100 transition-colors"
        >
          <RefreshCw className="w-3.5 h-3.5" />
          Refresh
        </button>
      </div>

      {/* Filter Bar */}
      <div className="bg-zinc-900/90 border border-zinc-800 rounded-xl p-4 shadow-lg backdrop-blur-md flex flex-wrap items-center gap-3">
        {/* Service Select */}
        <div className="flex items-center gap-2 bg-zinc-950/80 px-3 py-1.5 rounded-lg border border-zinc-700/60 text-xs font-mono">
          <span className="text-zinc-500">Service:</span>
          <select
            value={selectedService}
            onChange={(e) => setSelectedService(e.target.value)}
            className="bg-transparent text-zinc-200 outline-none cursor-pointer"
          >
            {services.map((s) => (
              <option key={s} value={s} className="bg-zinc-900 text-zinc-200">
                {s}
              </option>
            ))}
          </select>
        </div>

        {/* Status Select */}
        <div className="flex items-center gap-2 bg-zinc-950/80 px-3 py-1.5 rounded-lg border border-zinc-700/60 text-xs font-mono">
          <span className="text-zinc-500">Status:</span>
          <select
            value={selectedStatus}
            onChange={(e) => setSelectedStatus(e.target.value)}
            className="bg-transparent text-zinc-200 outline-none cursor-pointer"
          >
            <option value="all" className="bg-zinc-900">All</option>
            <option value="STATUS_CODE_OK" className="bg-zinc-900">OK</option>
            <option value="STATUS_CODE_ERROR" className="bg-zinc-900">Error</option>
          </select>
        </div>

        {/* Min Duration Filter */}
        <div className="flex items-center gap-2 bg-zinc-950/80 px-3 py-1.5 rounded-lg border border-zinc-700/60 text-xs font-mono">
          <span className="text-zinc-500">Min Duration:</span>
          <select
            value={minDurationMs}
            onChange={(e) => setMinDurationMs(Number(e.target.value))}
            className="bg-transparent text-zinc-200 outline-none cursor-pointer"
          >
            <option value={0} className="bg-zinc-900">All</option>
            <option value={50} className="bg-zinc-900">&gt; 50ms</option>
            <option value={100} className="bg-zinc-900">&gt; 100ms</option>
            <option value={250} className="bg-zinc-900">&gt; 250ms</option>
            <option value={500} className="bg-zinc-900">&gt; 500ms</option>
          </select>
        </div>

        {/* Operation Search Input */}
        <div className="flex-1 min-w-[240px] relative">
          <Search className="absolute left-3 top-1/2 transform -translate-y-1/2 w-3.5 h-3.5 text-zinc-500" />
          <input
            type="text"
            placeholder="Search operation name..."
            value={searchOperation}
            onChange={(e) => setSearchOperation(e.target.value)}
            className="w-full bg-zinc-950/80 border border-zinc-700/60 rounded-lg pl-9 pr-3 py-1.5 text-xs text-zinc-200 placeholder-zinc-500 outline-none focus:border-indigo-500"
          />
        </div>
      </div>

      {/* Spans Table */}
      <div className="bg-zinc-900/90 border border-zinc-800 rounded-xl overflow-hidden shadow-xl backdrop-blur-md">
        <div className="p-4 border-b border-zinc-800 bg-zinc-950/80 flex items-center justify-between text-xs font-mono text-zinc-400">
          <span>Query Results ({spans.length} spans matched)</span>
          <span>ClickHouse Columnar Search</span>
        </div>

        {isLoading ? (
          <div className="p-12 text-center text-zinc-400 text-xs font-mono">
            Searching ClickHouse traces_spans...
          </div>
        ) : spans.length > 0 ? (
          <div className="divide-y divide-zinc-800/60 overflow-x-auto">
            <table className="w-full text-left font-mono text-xs">
              <thead className="bg-zinc-950/50 text-zinc-400 border-b border-zinc-800">
                <tr>
                  <th className="px-4 py-2.5">Timestamp</th>
                  <th className="px-4 py-2.5">Service</th>
                  <th className="px-4 py-2.5">Operation</th>
                  <th className="px-4 py-2.5">Duration</th>
                  <th className="px-4 py-2.5">Status</th>
                  <th className="px-4 py-2.5">Trace ID</th>
                  <th className="px-4 py-2.5 text-right">Action</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-zinc-800/40 text-zinc-200">
                {spans.map((span) => {
                  const isError = span.statusCode === 'STATUS_CODE_ERROR' || (span.statusCode as string) === 'ERROR';
                  return (
                    <tr key={span.spanId} className="hover:bg-zinc-800/40 transition-colors">
                      <td className="px-4 py-3 text-zinc-400 whitespace-nowrap">
                        {formatTimestamp(span.timestamp || Date.now())}
                      </td>
                      <td className="px-4 py-3">
                        <span className="px-2 py-0.5 rounded text-[11px] bg-zinc-800 border border-zinc-700 text-zinc-300">
                          {span.serviceName}
                        </span>
                      </td>
                      <td className="px-4 py-3 font-semibold text-zinc-100 max-w-xs truncate" title={span.operationName}>
                        {span.operationName}
                      </td>
                      <td className="px-4 py-3 font-bold whitespace-nowrap">
                        <span className={span.durationMs > 200 ? 'text-red-400' : span.durationMs > 100 ? 'text-amber-400' : 'text-emerald-400'}>
                          {formatDuration(span.durationMs)}
                        </span>
                      </td>
                      <td className="px-4 py-3 whitespace-nowrap">
                        {isError ? (
                          <span className="inline-flex items-center gap-1 text-red-400 font-semibold text-[11px]">
                            <AlertCircle className="w-3.5 h-3.5" /> ERROR
                          </span>
                        ) : (
                          <span className="inline-flex items-center gap-1 text-emerald-400 font-semibold text-[11px]">
                            <CheckCircle2 className="w-3.5 h-3.5" /> OK
                          </span>
                        )}
                      </td>
                      <td className="px-4 py-3 text-zinc-500 font-mono text-[11px] truncate max-w-[120px]" title={span.traceId}>
                        {span.traceId}
                      </td>
                      <td className="px-4 py-3 text-right">
                        <Link
                          href={`/traces/${span.traceId}`}
                          className="inline-flex items-center gap-1 text-indigo-400 hover:text-indigo-300 font-semibold transition-colors"
                        >
                          View DAG <ArrowRight className="w-3 h-3" />
                        </Link>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        ) : (
          <div className="p-12 text-center text-zinc-500 text-xs font-mono">
            No traces matched current filter parameters.
          </div>
        )}
      </div>
    </div>
  );
}
