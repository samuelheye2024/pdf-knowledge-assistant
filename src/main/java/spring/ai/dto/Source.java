package spring.ai.dto;

/**
 * A single citation for a RAG answer: the original filename and the
 * 1-based physical page number the supporting chunk was extracted from.
 */
public record Source(String file, Integer page) {
}
