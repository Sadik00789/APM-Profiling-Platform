package com.apm.collector;

import com.apm.collector.ingestion.profiling.FoldedStackParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FoldedStackParserTest {

    private final FoldedStackParser parser = new FoldedStackParser();

    @Test
    @DisplayName("Should parse standard multi-line folded stack traces")
    void testStandardFoldedStackParsing() {
        String input = """
            # Pyroscope folded profile dump
            main;service.process;db.query 45
            main;service.process;crypto.hash 15
            
            main;healthCheck 10
            """;

        List<FoldedStackParser.ParsedFoldedSample> samples = parser.parse(input);

        assertEquals(3, samples.size());

        FoldedStackParser.ParsedFoldedSample s1 = samples.get(0);
        assertArrayEquals(new String[]{"main", "service.process", "db.query"}, s1.frames());
        assertEquals(45, s1.sampleCount());

        FoldedStackParser.ParsedFoldedSample s2 = samples.get(1);
        assertArrayEquals(new String[]{"main", "service.process", "crypto.hash"}, s2.frames());
        assertEquals(15, s2.sampleCount());

        FoldedStackParser.ParsedFoldedSample s3 = samples.get(2);
        assertArrayEquals(new String[]{"main", "healthCheck"}, s3.frames());
        assertEquals(10, s3.sampleCount());
    }

    @Test
    @DisplayName("Should handle empty and malformed lines safely")
    void testEmptyAndMalformed() {
        assertTrue(parser.parse(null).isEmpty());
        assertTrue(parser.parse("").isEmpty());
        assertTrue(parser.parse("   \n\n  ").isEmpty());

        List<FoldedStackParser.ParsedFoldedSample> res = parser.parse("singleFrameWithoutCount");
        assertEquals(1, res.size());
        assertEquals(1, res.get(0).sampleCount());
        assertArrayEquals(new String[]{"singleFrameWithoutCount"}, res.get(0).frames());
    }

    @Test
    @DisplayName("Should preserve frames containing spaces when trailing token is non-numeric and handle CRLF")
    void testFramesWithSpacesAndZeroCounts() {
        String input = "Thread Pool;operator new(unsigned long)\r\n"
                + "main;;subFrame; 0\r\n"
                + "main;;leafFrame 12\r\n";

        List<FoldedStackParser.ParsedFoldedSample> samples = parser.parse(input);
        assertEquals(2, samples.size());

        assertArrayEquals(new String[]{"Thread Pool", "operator new(unsigned long)"}, samples.get(0).frames());
        assertEquals(1L, samples.get(0).sampleCount());

        assertArrayEquals(new String[]{"main", "leafFrame"}, samples.get(1).frames());
        assertEquals(12L, samples.get(1).sampleCount());
    }
}
