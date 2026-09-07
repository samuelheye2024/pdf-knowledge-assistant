package spring.ai.dto;

/**
 * Request body for {@code POST /documents/ingest-directory}: scans
 * {@code directory} on disk for PDFs and adds them to the vector store,
 * reading each file in place — nothing is copied — so chunk metadata can
 * carry the file's real absolute path.
 *
 * @param directory an absolute (or working-directory-relative) path to a
 *                   folder containing PDFs
 * @param recursive whether to recurse into subfolders; defaults to
 *                  {@code true} when omitted, see {@link #recursiveOrDefault()}
 */
public record IngestDirectoryRequest(String directory, Boolean recursive) {

    public boolean recursiveOrDefault() {
        return recursive == null || recursive;
    }
}
