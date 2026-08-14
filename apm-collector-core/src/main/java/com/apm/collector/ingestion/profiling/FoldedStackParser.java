package com.apm.collector.ingestion.profiling;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
public class FoldedStackParser {

    public record ParsedFoldedSample(String[] frames, long sampleCount, String rawLine) {}

    /**
     * Parses multiline folded-stack format (Pyroscope/FlameGraph standard):
     * "java.lang.Thread.run;com.apm.Service.process 42\n..."
     */
    public List<ParsedFoldedSample> parse(String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }

        List<ParsedFoldedSample> results = new ArrayList<>();
        int len = text.length();
        int lineStart = 0;

        for (int i = 0; i <= len; i++) {
            if (i == len || text.charAt(i) == '\n' || text.charAt(i) == '\r') {
                if (i > lineStart) {
                    ParsedFoldedSample sample = parseLine(text, lineStart, i);
                    if (sample != null) {
                        results.add(sample);
                    }
                }
                lineStart = i + 1;
            }
        }

        return results;
    }

    private ParsedFoldedSample parseLine(String text, int start, int end) {
        // Trim leading and trailing whitespace within the line
        while (start < end && Character.isWhitespace(text.charAt(start))) {
            start++;
        }
        while (end > start && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }

        if (start >= end || text.charAt(start) == '#') {
            return null; // Empty line or comment
        }

        // Find last space separating frames and sample count
        int lastSpaceIdx = -1;
        for (int i = end - 1; i >= start; i--) {
            if (Character.isWhitespace(text.charAt(i))) {
                lastSpaceIdx = i;
                break;
            }
        }

        if (lastSpaceIdx == -1 || lastSpaceIdx == end - 1) {
            // No sample count found, default to 1 sample
            String lineStr = text.substring(start, end);
            String[] frames = splitFrames(text, start, end);
            return new ParsedFoldedSample(frames, 1L, lineStr);
        }

        // Parse sample count
        long sampleCount = 1L;
        try {
            sampleCount = Long.parseLong(text.substring(lastSpaceIdx + 1, end).trim());
        } catch (NumberFormatException ignored) {
            sampleCount = 1L;
        }

        String lineStr = text.substring(start, end);
        String[] frames = splitFrames(text, start, lastSpaceIdx);

        return new ParsedFoldedSample(frames, sampleCount, lineStr);
    }

    private String[] splitFrames(String text, int start, int end) {
        List<String> list = new ArrayList<>(16);
        int frameStart = start;

        for (int i = start; i <= end; i++) {
            if (i == end || text.charAt(i) == ';') {
                if (i > frameStart) {
                    String frame = text.substring(frameStart, i).trim();
                    if (!frame.isEmpty()) {
                        list.add(frame);
                    }
                }
                frameStart = i + 1;
            }
        }

        return list.toArray(new String[0]);
    }
}
