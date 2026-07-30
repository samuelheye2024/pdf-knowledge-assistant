package spring.ai.model;

/**
 * Metadata keys used on {@link org.springframework.ai.document.Document}
 * instances as they flow from ingestion ({@code DocumentUploadController})
 * through to RAG citation extraction ({@code ChatController}).
 *
 * <p>Centralizing these keys avoids the two controllers silently drifting
 * out of sync via duplicated string literals.
 */
public final class DocumentMetadataKeys {

    /** Original filename the chunk was extracted from. */
    public static final String SOURCE = "source";

    /** 1-based physical page number within the source PDF. */
    public static final String PAGE = "page";

    private DocumentMetadataKeys() {
    }
}
