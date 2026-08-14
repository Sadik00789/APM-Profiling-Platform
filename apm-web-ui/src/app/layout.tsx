import type { Metadata } from 'next';
import Link from 'next/link';
import './globals.css';
import { Flame, GitGraph, Activity, Network } from 'lucide-react';
import { ChaosControlHUD } from '../components/common/ChaosControlHUD';

export const metadata: Metadata = {
  title: 'Distributed APM & Continuous Profiling Platform',
  description: 'High-throughput distributed tracing, flame graph continuous profiling, and real-time observability platform.',
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="en" className="dark">
      <body className="bg-zinc-950 text-zinc-100 min-h-screen flex flex-col antialiased selection:bg-amber-500/30 selection:text-amber-200">
        {/* Top Sticky Navigation */}
        <header className="sticky top-0 z-40 w-full border-b border-zinc-800/80 bg-zinc-950/80 backdrop-blur-xl">
          <div className="max-w-7xl mx-auto flex h-14 items-center justify-between px-4 sm:px-6">
            {/* Logo */}
            <div className="flex items-center gap-6">
              <Link href="/" className="flex items-center gap-2.5 group">
                <div className="p-1.5 rounded-lg bg-gradient-to-br from-amber-500 to-orange-600 shadow-md shadow-amber-500/20 group-hover:scale-105 transition-transform">
                  <Flame className="w-4 h-4 text-zinc-950 fill-zinc-950" />
                </div>
                <div className="flex flex-col">
                  <span className="font-bold text-sm tracking-tight bg-gradient-to-r from-zinc-100 via-zinc-200 to-zinc-400 bg-clip-text text-transparent">
                    APM Platform
                  </span>
                  <span className="text-[9px] font-mono text-zinc-500 -mt-0.5">
                    ClickHouse • Java 21 • Profiler
                  </span>
                </div>
              </Link>

              {/* Nav Links */}
              <nav className="hidden md:flex items-center gap-1 text-xs font-medium">
                <Link
                  href="/"
                  className="px-3 py-1.5 rounded-lg text-zinc-300 hover:text-zinc-100 hover:bg-zinc-800/60 transition-colors flex items-center gap-1.5"
                >
                  <Activity className="w-3.5 h-3.5 text-zinc-400" />
                  Overview
                </Link>

                <Link
                  href="/traces"
                  className="px-3 py-1.5 rounded-lg text-zinc-300 hover:text-zinc-100 hover:bg-zinc-800/60 transition-colors flex items-center gap-1.5"
                >
                  <GitGraph className="w-3.5 h-3.5 text-indigo-400" />
                  Traces
                </Link>

                <Link
                  href="/profiling"
                  className="px-3 py-1.5 rounded-lg text-zinc-300 hover:text-zinc-100 hover:bg-zinc-800/60 transition-colors flex items-center gap-1.5"
                >
                  <Flame className="w-3.5 h-3.5 text-amber-400" />
                  Continuous Profiling
                </Link>

                <Link
                  href="/topology"
                  className="px-3 py-1.5 rounded-lg text-zinc-300 hover:text-zinc-100 hover:bg-zinc-800/60 transition-colors flex items-center gap-1.5"
                >
                  <Network className="w-3.5 h-3.5 text-emerald-400" />
                  Topology
                </Link>
              </nav>
            </div>

            {/* Right Status */}
            <div className="flex items-center gap-3">
              <div className="flex items-center gap-1.5 text-xs font-mono text-zinc-400 bg-zinc-900 border border-zinc-800 px-2.5 py-1 rounded-md">
                <span className="w-2 h-2 rounded-full bg-emerald-500 animate-pulse"></span>
                <span>ClickHouse Columnar Engine</span>
              </div>
            </div>
          </div>

          {/* Sticky Chaos Control HUD */}
          <ChaosControlHUD />
        </header>

        {/* Main Content Area */}
        <main className="flex-1 max-w-7xl w-full mx-auto p-4 sm:p-6">
          {children}
        </main>
      </body>
    </html>
  );
}
