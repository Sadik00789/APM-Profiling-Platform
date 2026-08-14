export function hashStringToHue(str: string): number {
  let hash = 0;
  for (let i = 0; i < str.length; i++) {
    hash = (hash << 5) - hash + str.charCodeAt(i);
    hash |= 0;
  }
  return Math.abs(hash) % 360;
}

export function getFrameColor(
  name: string,
  pkg: string,
  isHighlighted = false,
  diffPercent?: number
): string {
  if (isHighlighted) {
    return 'hsl(48, 96%, 53%)'; // Gold search match highlight
  }

  // Differential mode coloring (Vibrant Red/Coral for regressions, Cool Blue/Slate for improvements)
  if (diffPercent != null && diffPercent !== 0) {
    if (diffPercent > 2) {
      const alpha = Math.min(1.0, 0.4 + (diffPercent / 100) * 0.6);
      return `rgba(239, 68, 68, ${alpha})`; // Vibrant Coral/Red regression
    } else if (diffPercent < -2) {
      const alpha = Math.min(1.0, 0.4 + (Math.abs(diffPercent) / 100) * 0.6);
      return `rgba(56, 189, 248, ${alpha})`; // Cool Sky/Slate Blue improvement
    }
    return 'hsl(215, 20%, 35%)'; // Neutral change
  }

  const lowName = (name || '').toLowerCase();
  const lowPkg = (pkg || '').toLowerCase();

  // Package-tailored color palettes
  if (lowPkg.startsWith('com.apm')) {
    return 'hsl(18, 85%, 54%)'; // Flame orange/crimson for internal app code
  }
  if (lowPkg.startsWith('org.springframework') || lowPkg.startsWith('io.netty')) {
    return 'hsl(192, 80%, 42%)'; // Cyan/Teal for framework & networking
  }
  if (lowPkg.startsWith('java') || lowPkg.startsWith('jdk') || lowPkg.startsWith('sun')) {
    return 'hsl(38, 80%, 50%)'; // Amber/Yellow for runtime & core JVM
  }
  if (lowPkg.includes('clickhouse') || lowPkg.includes('sql') || lowPkg.includes('jdbc')) {
    return 'hsl(270, 70%, 55%)'; // Purple for Database & Drivers
  }
  if (lowPkg.includes('jackson') || lowPkg.includes('json') || lowPkg.includes('crypto')) {
    return 'hsl(150, 70%, 40%)'; // Emerald green for serialization & crypto
  }

  // Deterministic fallback
  const hue = hashStringToHue(pkg || name);
  return `hsl(${hue}, 65%, 48%)`;
}
