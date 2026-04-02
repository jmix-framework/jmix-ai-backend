package io.jmix.ai.backend.vectorstore;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;

public class ChunkTextSplitter {

    private static final int HEADER_SCAN_LIMIT = 500;

    private final int maxChunkSize;
    private final int overlap;

    public ChunkTextSplitter(int maxChunkSize, int overlap) {
        this.maxChunkSize = maxChunkSize;
        this.overlap = overlap;
    }

    public List<Chunker.Chunk> split(Chunker.Chunk chunk) {
        String text = StringUtils.trimToEmpty(chunk.text());
        if (text.length() <= maxChunkSize) {
            return List.of(chunk);
        }

        HeaderAndBody headerAndBody = splitHeader(text);
        int bodyChunkSize = getBodyChunkSize(headerAndBody.header());
        List<String> parts = splitPlainText(headerAndBody.body(), bodyChunkSize);

        if (parts.isEmpty()) {
            return List.of(chunk);
        }

        return parts.stream()
                .map(part -> new Chunker.Chunk(headerAndBody.header() + part, chunk.anchor()))
                .toList();
    }

    private HeaderAndBody splitHeader(String text) {
        int scanLimit = Math.min(text.length(), HEADER_SCAN_LIMIT);
        int headerSeparator = text.substring(0, scanLimit).indexOf("\n\n");
        if (headerSeparator < 0) {
            return new HeaderAndBody("", text);
        }

        int bodyStart = headerSeparator + 2;
        return new HeaderAndBody(text.substring(0, bodyStart), text.substring(bodyStart));
    }

    private int getBodyChunkSize(String header) {
        int bodyChunkSize = maxChunkSize - header.length();
        return Math.max(bodyChunkSize, maxChunkSize / 2);
    }

    private List<String> splitPlainText(String text, int chunkSize) {
        String normalized = StringUtils.trimToEmpty(text);
        if (normalized.isEmpty()) {
            return List.of();
        }

        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < normalized.length()) {
            int maxEnd = Math.min(start + chunkSize, normalized.length());
            int end = findSplitEnd(normalized, start, maxEnd);
            String chunk = normalized.substring(start, end).trim();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }
            if (end >= normalized.length()) {
                break;
            }

            start = Math.max(end - overlap, start + 1);
            while (start < normalized.length() && Character.isWhitespace(normalized.charAt(start))) {
                start++;
            }
        }
        return chunks;
    }

    private int findSplitEnd(String text, int start, int maxEnd) {
        if (maxEnd >= text.length()) {
            return text.length();
        }

        int minPreferred = start + Math.max(1, (maxEnd - start) / 2);
        int boundary = findLastBoundary(text, minPreferred, maxEnd, "\n\n");
        if (boundary > start) {
            return boundary;
        }
        boundary = findLastBoundary(text, minPreferred, maxEnd, "\n");
        if (boundary > start) {
            return boundary;
        }
        boundary = findLastBoundary(text, minPreferred, maxEnd, ". ");
        if (boundary > start) {
            return boundary;
        }
        boundary = findLastBoundary(text, minPreferred, maxEnd, " ");
        if (boundary > start) {
            return boundary;
        }
        return maxEnd;
    }

    private int findLastBoundary(String text, int minPreferred, int maxEnd, String marker) {
        int idx = text.lastIndexOf(marker, maxEnd - 1);
        if (idx < minPreferred) {
            return -1;
        }
        return idx + marker.length();
    }

    private record HeaderAndBody(String header, String body) {
    }
}
