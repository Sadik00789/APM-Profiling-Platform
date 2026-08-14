import { FlameGraphNode } from '../../types/profile';

// Web Worker for off-thread flame graph parsing and D3 hierarchy flattening

self.onmessage = (event: MessageEvent) => {
  const { type, payload } = event.data;

  if (type === 'PARSE_FOLDED_STACK') {
    const root = parseFoldedTextToTree(payload.text);
    self.postMessage({ type: 'PARSE_COMPLETE', root });
  } else if (type === 'SEARCH_NODES') {
    const matchedNames = searchTree(payload.root, payload.query.toLowerCase());
    self.postMessage({ type: 'SEARCH_COMPLETE', matchedNames });
  }
};

function parseFoldedTextToTree(text: string): FlameGraphNode {
  const root: FlameGraphNode = {
    name: 'root',
    package: 'root',
    value: 0,
    selfValue: 0,
    selfPercent: 0,
    totalPercent: 100,
    depth: 0,
    children: [],
  };

  if (!text) return root;

  const lines = text.split(/\r?\n/);
  for (const line of lines) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith('#')) continue;

    const lastSpace = trimmed.lastIndexOf(' ');
    let frames: string[];
    let samples = 1;

    if (lastSpace !== -1) {
      frames = trimmed.substring(0, lastSpace).split(';');
      samples = parseInt(trimmed.substring(lastSpace + 1), 10) || 1;
    } else {
      frames = trimmed.split(';');
    }

    root.value += samples;
    let current = root;

    for (let i = 0; i < frames.length; i++) {
      const frameName = frames[i].trim();
      if (!frameName) continue;

      if (!current.children) current.children = [];
      let child = current.children.find((c) => c.name === frameName);
      if (!child) {
        child = {
          name: frameName,
          package: extractPkg(frameName),
          value: 0,
          selfValue: 0,
          selfPercent: 0,
          totalPercent: 0,
          depth: current.depth + 1,
          children: [],
        };
        current.children.push(child);
      }

      child.value += samples;
      if (i === frames.length - 1) {
        child.selfValue += samples;
      }
      current = child;
    }
  }

  calculatePercentages(root, root.value);
  return root;
}

function extractPkg(name: string): string {
  const clean = name.split('(')[0];
  const lastDot = clean.lastIndexOf('.');
  return lastDot > 0 ? clean.substring(0, lastDot) : 'default';
}

function calculatePercentages(node: FlameGraphNode, total: number) {
  if (total > 0) {
    node.totalPercent = (node.value / total) * 100;
    node.selfPercent = (node.selfValue / total) * 100;
  }
  if (node.children) {
    node.children.sort((a, b) => b.value - a.value);
    for (const child of node.children) {
      calculatePercentages(child, total);
    }
  }
}

function searchTree(node: FlameGraphNode, query: string): Set<string> {
  const matches = new Set<string>();
  if (!node || !query) return matches;

  function traverse(n: FlameGraphNode) {
    if (n.name.toLowerCase().includes(query) || (n.package && n.package.toLowerCase().includes(query))) {
      matches.add(n.name);
    }
    if (n.children) {
      for (const child of n.children) {
        traverse(child);
      }
    }
  }

  traverse(node);
  return matches;
}
