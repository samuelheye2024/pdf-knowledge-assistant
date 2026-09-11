package spring.ai.dto;

/**
 * Request body for {@code POST /documents/ingest-url}: downloads the PDF at
 * {@code url} and adds it to the vector store. This is the one ingestion
 * path that works for a caller on a different machine than the server —
 * the server fetches the URL itself over HTTP, so it never needs to see the
 * caller's local filesystem.
 *
 * @param url a public {@code http(s)} URL pointing directly at a PDF
 */
public record IngestUrlRequest(String url) {
}
