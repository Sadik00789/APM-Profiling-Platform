package com.apm.collector;

import com.apm.collector.engine.trie.CallStackTrie;
import com.apm.collector.engine.trie.FlameGraphSerializer;
import com.apm.collector.engine.trie.TrieNode;
import com.apm.contracts.profile.v1.DiffFlameGraphResponse;
import com.apm.contracts.profile.v1.FlameGraphNode;
import com.apm.contracts.profile.v1.ProfileType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CallStackTrieTest {

    @Test
    @DisplayName("Should insert stack frames, intern symbols into integers, and calculate sample counts correctly")
    void testInsertAndSampleCalculation() {
        CallStackTrie trie = new CallStackTrie();

        String[] stack1 = {"main", "service.process", "db.query"};
        String[] stack2 = {"main", "service.process", "crypto.hash"};
        String[] stack3 = {"main", "service.process"};

        trie.insert(stack1, 50);
        trie.insert(stack2, 30);
        trie.insert(stack3, 20);

        assertEquals(100, trie.getTotalSamples());
        assertEquals(3, trie.getMaxDepth());

        trie.finalizeMetrics();

        TrieNode root = trie.getRoot();
        assertEquals(100, root.getTotalValue());

        int mainSymId = trie.getOrInternSymbol("main");
        TrieNode mainNode = root.getChildren().get(mainSymId);
        assertNotNull(mainNode);
        assertEquals(100, mainNode.getTotalValue());
        assertEquals(100.0, mainNode.getTotalPercent());

        int processSymId = trie.getOrInternSymbol("service.process");
        TrieNode processNode = mainNode.getChildren().get(processSymId);
        assertNotNull(processNode);
        assertEquals(100, processNode.getTotalValue());
        assertEquals(20, processNode.getSelfValue());
        assertEquals(20.0, processNode.getSelfPercent());

        int dbSymId = trie.getOrInternSymbol("db.query");
        TrieNode dbNode = processNode.getChildren().get(dbSymId);
        assertNotNull(dbNode);
        assertEquals(50, dbNode.getTotalValue());
        assertEquals(50, dbNode.getSelfValue());
        assertEquals(50.0, dbNode.getTotalPercent());
    }

    @Test
    @DisplayName("Should compute differential profiles correctly (A vs B) even with different symbol intern order")
    void testDifferentialProfiling() {
        CallStackTrie baseTrie = new CallStackTrie();
        baseTrie.insert(new String[]{"main", "removedMethod"}, 40);
        baseTrie.insert(new String[]{"main", "expensiveMethod"}, 60);

        CallStackTrie compTrie = new CallStackTrie();
        // Insert new frame first so symbol IDs differ completely between baseTrie and compTrie
        compTrie.insert(new String[]{"main", "newOptimizedMethod"}, 50);
        compTrie.insert(new String[]{"main", "expensiveMethod"}, 150);

        DiffFlameGraphResponse diff = baseTrie.computeDiff(compTrie, "test-service", ProfileType.PROFILE_TYPE_CPU);

        assertEquals(100, diff.getBaselineTotal());
        assertEquals(200, diff.getComparisonTotal());
        assertEquals(100.0, diff.getOverallChangePercent());

        FlameGraphNode root = diff.getRoot();
        assertEquals("root", root.getName());
        assertEquals(100L, root.getDiffValue());

        FlameGraphNode mainNode = root.getChildrenList().stream()
                .filter(n -> "main".equals(n.getName()))
                .findFirst().orElseThrow();

        FlameGraphNode expensive = mainNode.getChildrenList().stream()
                .filter(n -> "expensiveMethod".equals(n.getName()))
                .findFirst().orElseThrow();
        assertEquals(90L, expensive.getDiffValue()); // 150 - 60
        assertEquals(15.0, expensive.getDiffPercent(), 0.01); // 75% - 60%

        FlameGraphNode newMethod = mainNode.getChildrenList().stream()
                .filter(n -> "newOptimizedMethod".equals(n.getName()))
                .findFirst().orElseThrow();
        assertEquals(50L, newMethod.getDiffValue()); // 50 - 0
        assertEquals(25.0, newMethod.getDiffPercent(), 0.01); // 25% - 0%

        FlameGraphNode removed = mainNode.getChildrenList().stream()
                .filter(n -> "removedMethod".equals(n.getName()))
                .findFirst().orElseThrow();
        assertEquals(0L, removed.getTotalValue());
        assertEquals(-40L, removed.getDiffValue()); // 0 - 40
        assertEquals(-40.0, removed.getDiffPercent(), 0.01); // 0% - 40%

        // Verify proto-to-nestedMap conversion preserves diff metadata
        Map<String, Object> diffMap = FlameGraphSerializer.toNestedMap(root);
        assertEquals("root", diffMap.get("name"));
        assertEquals(100L, diffMap.get("diffValue"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> children = (List<Map<String, Object>>) diffMap.get("children");
        assertEquals(1, children.size());
        assertEquals("main", children.get(0).get("name"));
    }

    @Test
    @DisplayName("Should serialize Trie into D3 nested map structure with resolved symbols")
    void testSerializationToNestedMap() {
        CallStackTrie trie = new CallStackTrie();
        trie.insert(new String[]{"com.apm.App.main", "com.apm.OrderService.run"}, 42);
        trie.finalizeMetrics();

        assertEquals(-1, trie.findSymbolId("nonexistent.Method"));
        assertTrue(trie.findSymbolId("com.apm.App.main") > 0);

        Map<String, Object> map = FlameGraphSerializer.toNestedMap(trie.getRoot(), trie);
        assertNotNull(map);
        assertEquals("root", map.get("name"));
        assertTrue(map.containsKey("children"));
    }

    @Test
    @DisplayName("Should prune low-percentage branches without corrupting remaining nodes")
    void testPruning() {
        CallStackTrie trie = new CallStackTrie();
        trie.insert(new String[]{"main", "hotPath"}, 99);
        trie.insert(new String[]{"main", "coldPath"}, 1);

        trie.prune(5.0);
        assertEquals(3, trie.countNodes()); // root, main, hotPath
        int mainSym = trie.findSymbolId("main");
        TrieNode mainNode = trie.getRoot().getChildren().get(mainSym);
        assertEquals(1, mainNode.getChildren().size());
    }
}
