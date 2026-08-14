export function formatDuration(ms: number): string {
  if (ms == null || isNaN(ms)) return '0 ms';
  if (ms < 0.001) {
    return `${(ms * 1_000_000).toFixed(0)} ns`;
  }
  if (ms < 1.0) {
    return `${(ms * 1_000).toFixed(1)} µs`;
  }
  if (ms < 1000.0) {
    return `${ms.toFixed(2)} ms`;
  }
  return `${(ms / 1000.0).toFixed(2)} s`;
}

export function formatNano(nano: number): string {
  return formatDuration(nano / 1_000_000.0);
}

export function formatPercent(pct: number): string {
  if (pct == null || isNaN(pct)) return '0.0%';
  return `${pct > 0 ? '+' : ''}${pct.toFixed(2)}%`;
}

export function formatNumber(num: number): string {
  if (num == null || isNaN(num)) return '0';
  if (num >= 1_000_000) return `${(num / 1_000_000).toFixed(1)}M`;
  if (num >= 1_000) return `${(num / 1_000).toFixed(1)}k`;
  return num.toLocaleString();
}

/**
 * Hardened timestamp parser supporting:
 * - Nanoseconds (> 1e16)
 * - Microseconds (> 1e13)
 * - Milliseconds (> 1e10)
 * - Seconds (< 1e10)
 * - ISO date strings / ClickHouse date strings (e.g. "2026-08-15 03:15:00")
 */
export function parseTimestampToMillis(input: number | string | undefined | null): number {
  if (input == null || input === '') {
    return Date.now();
  }

  if (typeof input === 'number') {
    if (isNaN(input) || input <= 0) return Date.now();
    if (input > 1e16) return Math.floor(input / 1_000_000); // Nanoseconds
    if (input > 1e13) return Math.floor(input / 1_000);     // Microseconds
    if (input > 1e10) return input;                         // Milliseconds
    return input * 1000;                                    // Seconds
  }

  // String handling
  const num = Number(input);
  if (!isNaN(num) && num > 0) {
    return parseTimestampToMillis(num);
  }

  const parsedDate = new Date(input.replace(' ', 'T'));
  const time = parsedDate.getTime();
  return isNaN(time) ? Date.now() : time;
}

export function formatTimestamp(ts: number | string | undefined | null): string {
  if (ts == null || ts === '') return 'Just now';
  const millis = parseTimestampToMillis(ts);
  const date = new Date(millis);
  if (isNaN(date.getTime())) return 'Just now';

  return date.toLocaleTimeString([], {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    fractionalSecondDigits: 3,
  });
}

export function formatDateTime(ts: number | string | undefined | null): string {
  if (ts == null || ts === '') return 'Just now';
  const millis = parseTimestampToMillis(ts);
  const date = new Date(millis);
  if (isNaN(date.getTime())) return 'Just now';

  return date.toLocaleString([], {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  });
}