package com.apm.collector.engine.trie;

import com.apm.contracts.profile.v1.DiffFlameGraphResponse;
import com.apm.contracts.profile.v1.FlameGraphResponse;
import com.apm.contracts.profile.v1.ProfileType;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import lombok.Getter;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class CallStackTrie {

    public static final int ROOT_SYMBOL_ID = 0;
    public static final String ROOT_NAME = "root";

    @Getter
    private final TrieNode root;
    private final AtomicLong totalSamples = new AtomicLong(0);
    private final AtomicInteger maxDepth = new AtomicInteger(0);

    // Fastutil Symbol Intern Table (prevents GC thrashing from millions of repeated frame strings)
    private final Object2IntOpenHashMap<String> stringToId = new Object2IntOpenHashMap<>();
    private final Int2ObjectOpenHashMap<String> idToString = new Int2ObjectOpenHashMap<>();
    private final AtomicInteger nextSymbolId = new AtomicInteger(1);
    private final ReentrantReadWriteLock symbolLock = new ReentrantReadWriteLock();

    public CallStackTrie() {
        stringToId.defaultReturnValue(-1);
        stringToId.put(ROOT_NAME, ROOT_SYMBOL_ID);
        idToString.put(ROOT_SYMBOL_ID, ROOT_NAME);
        this.root = new TrieNode(ROOT_SYMBOL_ID, 0);
    }

    public int getOrInternSymbol(String frameName) {
        if (frameName == null || frameName.isEmpty() || ROOT_NAME.equals(frameName)) {
            return ROOT_SYMBOL_ID;
        }

        symbolLock.readLock().lock();
        try {
            int existing = stringToId.getInt(frameName);
            if (existing != -1) {
                return existing;
            }
        } finally {
            symbolLock.readLock().unlock();
        }

        symbolLock.writeLock().lock();
        try {
            int existing = stringToId.getInt(frameName);
            if (existing != -1) {
                return existing;
            }
            int id = nextSymbolId.getAndIncrement();
            stringToId.put(frameName, id);
            idToString.put(id, frameName);
            return id;
        } finally {
            symbolLock.writeLock().unlock();
        }
    }

    public String resolveSymbol(int symbolId) {
        if (symbolId == ROOT_SYMBOL_ID) {
            return ROOT_NAME;
        }
        symbolLock.readLock().lock();
        try {
            String str = idToString.get(symbolId);
            return (str != null) ? str : "unknown-frame";
        } finally {
            symbolLock.readLock().unlock();
        }
    }

    /**
     * Inserts a call stack array (ordered from root frame to leaf frame) with its sample count.
     */
    public void insert(String[] frames, long sampleCount) {
        if (frames == null || frames.length == 0 || sampleCount <= 0) {
            return;
        }

        totalSamples.addAndGet(sampleCount);
        root.incrementValues(sampleCount, false);

        TrieNode current = root;
        for (int i = 0; i < frames.length; i++) {
            int symbolId = getOrInternSymbol(frames[i]);
            boolean isLeaf = (i == frames.length - 1);
            current = current.getOrCreateChild(symbolId);
            current.incrementValues(sampleCount, isLeaf);
        }

        maxDepth.accumulateAndGet(frames.length, Math::max);
    }

    public void insert(List<String> frames, long sampleCount) {
        if (frames != null) {
            insert(frames.toArray(new String[0]), sampleCount);
        }
    }

    public void finalizeMetrics() {
        long total = totalSamples.get();
        root.recalculateMetrics(total);
    }

    public void prune(double minPercentThreshold) {
        finalizeMetrics();
        root.pruneBelowThreshold(minPercentThreshold);
    }

    public long getTotalSamples() {
        return totalSamples.get();
    }

    public int getMaxDepth() {
        return maxDepth.get();
    }

    public int countNodes() {
        return countSubtreeNodes(root);
    }

    private int countSubtreeNodes(TrieNode node) {
        int count = 1;
        synchronized (node) {
            for (TrieNode child : node.getChildren().values()) {
                count += countSubtreeNodes(child);
            }
        }
        return count;
    }

    /**
     * Computes differential profile comparison (this = baseline, other = comparison).
     */
    public DiffFlameGraphResponse computeDiff(CallStackTrie comparisonTrie, String serviceName, ProfileType profileType) {
        this.finalizeMetrics();
        comparisonTrie.finalizeMetrics();

        long baselineTotal = this.getTotalSamples();
        long comparisonTotal = comparisonTrie.getTotalSamples();

        TrieNode diffRoot = buildDiffNode(this.root, comparisonTrie.getRoot(), baselineTotal, comparisonTotal, 0, comparisonTrie);

        double overallChange = 0.0;
        if (baselineTotal > 0) {
            overallChange = ((double) (comparisonTotal - baselineTotal) / baselineTotal) * 100.0;
        }

        return DiffFlameGraphResponse.newBuilder()
                .setServiceName(serviceName != null ? serviceName : "unknown")
                .setProfileType(profileType != null ? profileType : ProfileType.PROFILE_TYPE_CPU)
                .setBaselineTotal(baselineTotal)
                .setComparisonTotal(comparisonTotal)
                .setOverallChangePercent(overallChange)
                .setRoot(FlameGraphSerializer.toProtoNode(diffRoot, this))
                .build();
    }

    private TrieNode buildDiffNode(
            TrieNode baseNode,
            TrieNode compNode,
            long baseTotal,
            long compTotal,
            int depth,
            CallStackTrie compTrie) {

        int symbolId = (compNode != null) ? compNode.getSymbolId() : (baseNode != null ? baseNode.getSymbolId() : ROOT_SYMBOL_ID);
        TrieNode diffNode = new TrieNode(symbolId, depth);

        long baseVal = (baseNode != null) ? baseNode.getTotalValue() : 0L;
        long compVal = (compNode != null) ? compNode.getTotalValue() : 0L;
        long baseSelf = (baseNode != null) ? baseNode.getSelfValue() : 0L;
        long compSelf = (compNode != null) ? compNode.getSelfValue() : 0L;

        diffNode.setTotalValue(compVal);
        diffNode.setSelfValue(compSelf);
        diffNode.setDiffValue(compVal - baseVal);

        double basePct = (baseTotal > 0) ? ((double) baseVal / baseTotal * 100.0) : 0.0;
        double compPct = (compTotal > 0) ? ((double) compVal / compTotal * 100.0) : 0.0;
        diffNode.setTotalPercent(compPct);
        diffNode.setDiffPercent(compPct - basePct);

        IntSet allChildSymbolIds = new IntOpenHashSet();
        if (baseNode != null) {
            synchronized (baseNode) {
                allChildSymbolIds.addAll(baseNode.getChildren().keySet());
            }
        }
        if (compNode != null) {
            synchronized (compNode) {
                for (int compSymId : compNode.getChildren().keySet()) {
                    String frameName = compTrie.resolveSymbol(compSymId);
                    int mappedBaseSymId = this.getOrInternSymbol(frameName);
                    allChildSymbolIds.add(mappedBaseSymId);
                }
            }
        }

        for (int childSymId : allChildSymbolIds) {
            String frameName = this.resolveSymbol(childSymId);
            TrieNode baseChild = (baseNode != null) ? baseNode.getChildren().get(childSymId) : null;
            
            int compMappedSymId = compTrie.getOrInternSymbol(frameName);
            TrieNode compChild = (compNode != null) ? compNode.getChildren().get(compMappedSymId) : null;

            TrieNode diffChild = buildDiffNode(baseChild, compChild, baseTotal, compTotal, depth + 1, compTrie);
            diffNode.getChildren().put(childSymId, diffChild);
        }

        return diffNode;
    }

    public FlameGraphResponse toProtoResponse(String serviceName, ProfileType profileType, long startSec, long endSec) {
        finalizeMetrics();
        return FlameGraphResponse.newBuilder()
                .setServiceName(serviceName)
                .setProfileType(profileType)
                .setStartTimeUnixSec(startSec)
                .setEndTimeUnixSec(endSec)
                .setTotalSamples(totalSamples.get())
                .setRoot(FlameGraphSerializer.toProtoNode(root, this))
                .setMaxDepth(maxDepth.get())
                .setTotalNodes(countNodes())
                .build();
    }
}
