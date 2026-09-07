package spring.ai.dto;

/**
 * A single citation for a RAG answer: the original filename, the 1-based
 * physical page number the supporting chunk was extracted from, and — when
 * the document was ingested by directory path rather than browser upload —
 * the file's real absolute path on disk.
 */
public record Source(String file, Integer page, String path) {
}
