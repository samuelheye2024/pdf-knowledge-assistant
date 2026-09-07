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

    /**
     * The file's real absolute path on disk, set only when the document was
     * ingested by directory path ({@code POST /documents/ingest-directory}).
     * Browser uploads ({@code POST /documents}) never have this set, since
     * browsers don't expose a selected file's real location to JavaScript.
     */
    public static final String ABSOLUTE_PATH = "absolutePath";

    private DocumentMetadataKeys() {
    }
}
