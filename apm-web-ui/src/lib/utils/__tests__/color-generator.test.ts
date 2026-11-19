import { describe, expect, it } from 'vitest';
import { getFrameColor, hashStringToHue } from '../color-generator';

describe('color-generator', () => {
  it('returns highlight gold when highlighted regardless of diff or package', () => {
    expect(getFrameColor('anyMethod', 'com.apm.order', true, 50)).toBe('hsl(48, 96%, 53%)');
  });

  it('colors regressions red and optimizations blue in diff mode', () => {
    expect(getFrameColor('slow', 'com.apm', false, 50)).toContain('rgba(239, 68, 68,');
    expect(getFrameColor('fast', 'com.apm', false, -50)).toContain('rgba(56, 189, 248,');
    expect(getFrameColor('flat', 'com.apm', false, 1)).toBe('hsl(215, 20%, 35%)');
  });

  it('maps package families deterministically', () => {
    expect(getFrameColor('handle', 'com.apm.order', false)).toBe('hsl(18, 85%, 54%)');
    expect(getFrameColor('loop', 'io.netty.channel', false)).toBe('hsl(192, 80%, 42%)');
    expect(getFrameColor('run', 'java.lang', false)).toBe('hsl(38, 80%, 50%)');
    expect(getFrameColor('query', 'com.clickhouse.jdbc', false)).toBe('hsl(270, 70%, 55%)');
    expect(getFrameColor('write', 'com.fasterxml.jackson', false)).toBe('hsl(150, 70%, 40%)');
    expect(hashStringToHue('custom.pkg')).toBe(hashStringToHue('custom.pkg'));
  });
});
