import { describe, expect, it } from 'vitest';
import {
  formatDuration,
  formatNano,
  formatNumber,
  formatPercent,
  parseTimestampToMillis,
} from '../formatters';

describe('formatters', () => {
  it('formats durations across ns, us, ms, s and guards negative values', () => {
    expect(formatDuration(-5)).toBe('0 ms');
    expect(formatDuration(0)).toBe('0 ms');
    expect(formatDuration(0.0005)).toBe('500 ns');
    expect(formatDuration(0.45)).toBe('450.0 µs');
    expect(formatDuration(42.123)).toBe('42.12 ms');
    expect(formatDuration(1500)).toBe('1.50 s');
    expect(formatNano(2_500_000)).toBe('2.50 ms');
  });

  it('formats positive and negative counts cleanly', () => {
    expect(formatNumber(42)).toBe('42');
    expect(formatNumber(1_500)).toBe('1.5k');
    expect(formatNumber(-5_200)).toBe('-5.2k');
    expect(formatNumber(2_500_000)).toBe('2.5M');
    expect(formatNumber(-2_500_000)).toBe('-2.5M');
  });

  it('formats percentages with explicit positive sign', () => {
    expect(formatPercent(12.345)).toBe('+12.35%');
    expect(formatPercent(-7.5)).toBe('-7.50%');
    expect(formatPercent(0)).toBe('0.00%');
  });

  it('parses nanosecond, microsecond, millisecond, second, and string timestamps', () => {
    expect(parseTimestampToMillis(1_700_000_000_000_000_000)).toBe(1_700_000_000_000);
    expect(parseTimestampToMillis(1_700_000_000_000_000)).toBe(1_700_000_000_000);
    expect(parseTimestampToMillis(1_700_000_000_000)).toBe(1_700_000_000_000);
    expect(parseTimestampToMillis(1_700_000_000)).toBe(1_700_000_000_000);
    expect(parseTimestampToMillis('1700000000')).toBe(1_700_000_000_000);
  });
});
