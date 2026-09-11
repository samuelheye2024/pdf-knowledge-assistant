package spring.ai.dto;

/**
 * A single citation for a RAG answer: the original filename, the 1-based
 * physical page number the supporting chunk was extracted from, and the
 * public URL it was downloaded from ({@code POST /documents/ingest-url}).
 */
public record Source(String file, Integer page, String sourceUrl) {
}
