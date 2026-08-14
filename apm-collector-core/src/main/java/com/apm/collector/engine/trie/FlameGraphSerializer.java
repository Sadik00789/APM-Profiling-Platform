package com.apm.collector.engine.trie;

import com.apm.contracts.profile.v1.FlameGraphNode;

import java.util.*;

public final class FlameGraphSerializer {

    private FlameGraphSerializer() {}

    public static FlameGraphNode toProtoNode(TrieNode node, CallStackTrie trie) {
        if (node == null) {
            return FlameGraphNode.getDefaultInstance();
        }

        String name = trie.resolveSymbol(node.getSymbolId());
        String packageName = extractPackage(name);

        FlameGraphNode.Builder builder = FlameGraphNode.newBuilder()
                .setName(name)
                .setPackageName(packageName)
                .setSelfValue(node.getSelfValue())
                .setTotalValue(node.getTotalValue())
                .setSelfPercent(node.getSelfPercent())
                .setTotalPercent(node.getTotalPercent())
                .setDepth(node.getDepth())
                .setDiffValue(node.getDiffValue())
                .setDiffPercent(node.getDiffPercent());

        List<TrieNode> sortedChildren;
        synchronized (node) {
            sortedChildren = new ArrayList<>(node.getChildren().values());
        }
        sortedChildren.sort(Comparator.comparingLong(TrieNode::getTotalValue).reversed());

        for (TrieNode child : sortedChildren) {
            builder.addChildren(toProtoNode(child, trie));
        }

        return builder.build();
    }

    public static Map<String, Object> toNestedMap(TrieNode node, CallStackTrie trie) {
        if (node == null) return Collections.emptyMap();

        String name = trie.resolveSymbol(node.getSymbolId());
        String packageName = extractPackage(name);

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name);
        map.put("package", packageName);
        map.put("value", node.getTotalValue());
        map.put("selfValue", node.getSelfValue());
        map.put("selfPercent", Math.round(node.getSelfPercent() * 100.0) / 100.0);
        map.put("totalPercent", Math.round(node.getTotalPercent() * 100.0) / 100.0);
        map.put("depth", node.getDepth());
        map.put("diffValue", node.getDiffValue());
        map.put("diffPercent", Math.round(node.getDiffPercent() * 100.0) / 100.0);

        List<TrieNode> sortedChildren;
        synchronized (node) {
            sortedChildren = new ArrayList<>(node.getChildren().values());
        }
        sortedChildren.sort(Comparator.comparingLong(TrieNode::getTotalValue).reversed());

        if (!sortedChildren.isEmpty()) {
            List<Map<String, Object>> childrenList = new ArrayList<>(sortedChildren.size());
            for (TrieNode child : sortedChildren) {
                childrenList.add(toNestedMap(child, trie));
            }
            map.put("children", childrenList);
        }

        return map;
    }

    public static String extractPackage(String frameName) {
        if (frameName == null || frameName.isEmpty() || "root".equals(frameName)) {
            return "root";
        }
        int parenIdx = frameName.indexOf('(');
        String clean = (parenIdx > 0) ? frameName.substring(0, parenIdx) : frameName;
        int lastDot = clean.lastIndexOf('.');
        if (lastDot > 0) {
            int secondLastDot = clean.lastIndexOf('.', lastDot - 1);
            return (secondLastDot > 0) ? clean.substring(0, secondLastDot) : clean.substring(0, lastDot);
        }
        return "default";
    }
}
