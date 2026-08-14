package com.apm.collector.engine.trie;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TrieNode {

    private final int symbolId;
    private final int depth;
    private long selfValue;
    private long totalValue;
    private double selfPercent;
    private double totalPercent;
    private long diffValue;
    private double diffPercent;
    private final Int2ObjectOpenHashMap<TrieNode> children = new Int2ObjectOpenHashMap<>(4);

    public TrieNode(int symbolId, int depth) {
        this.symbolId = symbolId;
        this.depth = depth;
        this.selfValue = 0L;
        this.totalValue = 0L;
        this.selfPercent = 0.0;
        this.totalPercent = 0.0;
        this.diffValue = 0L;
        this.diffPercent = 0.0;
    }

    public synchronized TrieNode getOrCreateChild(int childSymbolId) {
        TrieNode child = children.get(childSymbolId);
        if (child == null) {
            child = new TrieNode(childSymbolId, this.depth + 1);
            children.put(childSymbolId, child);
        }
        return child;
    }

    public synchronized void incrementValues(long samples, boolean isLeaf) {
        this.totalValue += samples;
        if (isLeaf) {
            this.selfValue += samples;
        }
    }

    public synchronized void recalculateMetrics(long rootTotal) {
        if (rootTotal > 0) {
            this.totalPercent = (double) this.totalValue / rootTotal * 100.0;
            this.selfPercent = (double) this.selfValue / rootTotal * 100.0;
        } else {
            this.totalPercent = 0.0;
            this.selfPercent = 0.0;
        }

        for (TrieNode child : children.values()) {
            child.recalculateMetrics(rootTotal);
        }
    }

    public synchronized void pruneBelowThreshold(double minTotalPercent) {
        children.values().removeIf(child -> child.getTotalPercent() < minTotalPercent);
        for (TrieNode child : children.values()) {
            child.pruneBelowThreshold(minTotalPercent);
        }
    }
}
