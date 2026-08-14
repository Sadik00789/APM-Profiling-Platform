package com.apm.collector;

import com.apm.collector.engine.trie.CallStackTrie;
import com.apm.collector.engine.trie.FlameGraphSerializer;
import com.apm.collector.engine.trie.TrieNode;
import com.apm.contracts.profile.v1.DiffFlameGraphResponse;
import com.apm.contracts.profile.v1.ProfileType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
    @DisplayName("Should compute differential profiles correctly (A vs B) with symbol resolution")
    void testDifferentialProfiling() {
        CallStackTrie baseTrie = new CallStackTrie();
        baseTrie.insert(new String[]{"main", "expensiveMethod"}, 100);

        CallStackTrie compTrie = new CallStackTrie();
        compTrie.insert(new String[]{"main", "expensiveMethod"}, 150);
        compTrie.insert(new String[]{"main", "newOptimizedMethod"}, 50);

        DiffFlameGraphResponse diff = baseTrie.computeDiff(compTrie, "test-service", ProfileType.PROFILE_TYPE_CPU);

        assertEquals(100, diff.getBaselineTotal());
        assertEquals(200, diff.getComparisonTotal());
        assertEquals(100.0, diff.getOverallChangePercent()); // (200 - 100) / 100 * 100 = 100%
        assertNotNull(diff.getRoot());
    }

    @Test
    @DisplayName("Should serialize Trie into D3 nested map structure with resolved symbols")
    void testSerializationToNestedMap() {
        CallStackTrie trie = new CallStackTrie();
        trie.insert(new String[]{"com.apm.App.main", "com.apm.OrderService.run"}, 42);
        trie.finalizeMetrics();

        Map<String, Object> map = FlameGraphSerializer.toNestedMap(trie.getRoot(), trie);
        assertNotNull(map);
        assertEquals("root", map.get("name"));
        assertTrue(map.containsKey("children"));
    }
}
