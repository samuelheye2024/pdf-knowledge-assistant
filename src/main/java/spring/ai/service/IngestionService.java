package spring.ai.service;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import spring.ai.model.DocumentMetadataKeys;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Scans a directory for PDFs and adds them to the vector store, reading each
 * file in place off disk (never copied or uploaded) so chunk metadata can
 * carry the file's real absolute path.
 *
 * <p>This is the one place that logic lives — both {@code POST
 * /documents/ingest-directory} ({@link spring.ai.controller.DocumentUploadController})
 * and the chat-callable {@code ingestDirectory} tool
 * ({@link spring.ai.tool.IngestionTools}) call into it, so the button-driven
 * folder browser and "please ingest ~/Documents/legal" in the chat box can
 * never behave differently from one another.
 */
@Service
public class IngestionService {

    private static final String PDF_EXTENSION = ".pdf";

    private final VectorStore vectorStore;

    public IngestionService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * Resolves {@code rawPath} (expanding a leading {@code ~} to the user's
     * home directory, since that's how paths are naturally typed in chat —
     * the REST endpoint's folder browser never sends one, so this is a no-op
     * there), scans it for PDFs, reads and embeds each one, and adds the
     * resulting chunks to the vector store.
     *
     * @throws IllegalArgumentException if {@code rawPath} is blank, doesn't
     *         resolve to a directory, or that directory has no PDFs in it
     * @throws IOException if the directory can't be scanned
     */
    public IngestResult ingestDirectory(String rawPath, boolean recursive) throws IOException {
        if (rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException("A non-blank directory path is required");
        }

        Path directory = resolve(rawPath);

        if (!Files.isDirectory(directory)) {
            throw new IllegalArgumentException("Not a directory: " + directory);
        }

        List<Path> pdfFiles = findPdfFiles(directory, recursive);

        if (pdfFiles.isEmpty()) {
            throw new IllegalArgumentException("No PDF files found in: " + directory);
        }

        TextSplitter textSplitter = new TokenTextSplitter();
        List<Document> allChunks = new ArrayList<>();
        List<String> ingested = new ArrayList<>();
        List<Map<String, String>> failed = new ArrayList<>();

        for (Path pdfFile : pdfFiles) {
            try {
                allChunks.addAll(readAndSplit(pdfFile, textSplitter));
                ingested.add(pdfFile.getFileName().toString());
            } catch (IOException e) {
                failed.add(Map.of("file", pdfFile.toString(), "error", String.valueOf(e.getMessage())));
            }
        }

        if (!allChunks.isEmpty()) {
            vectorStore.add(allChunks);
        }

        return new IngestResult(directory.toString(), ingested.size(), allChunks.size(), ingested, failed);
    }

    /** Expands a leading {@code ~} to the user's home directory, then normalizes to an absolute path. */
    private Path resolve(String rawPath) {
        String expanded = rawPath.equals("~") || rawPath.startsWith("~/") || rawPath.startsWith("~\\")
                ? System.getProperty("user.home") + rawPath.substring(1)
                : rawPath;
        return Path.of(expanded).toAbsolutePath().normalize();
    }

    private List<Path> findPdfFiles(Path directory, boolean recursive) throws IOException {
        try (Stream<Path> stream = recursive ? Files.walk(directory) : Files.list(directory)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(PDF_EXTENSION))
                    .sorted()
                    .toList();
        }
    }

    /**
     * Reads a single PDF directly from disk by path (one {@link Document}
     * per physical page), stamps it with filename/page/absolute-path
     * metadata, and splits it into embeddable chunks. The file is read in
     * place, never copied.
     */
    private List<Document> readAndSplit(Path pdfFile, TextSplitter textSplitter) throws IOException {
        Resource resource = new FileSystemResource(pdfFile);

        PdfDocumentReaderConfig config = PdfDocumentReaderConfig
                .builder()
                .withPagesPerDocument(1)
                .build();

        List<Document> pages = new PagePdfDocumentReader(resource, config).get();
        stampSourceMetadata(pages, pdfFile.getFileName().toString(), pdfFile.toString());

        return textSplitter.apply(pages);
    }

    /**
     * Stamps our own source/page/path metadata explicitly (rather than
     * relying on whatever keys the reader may or may not set) so citations
     * are reliable downstream when answering RAG questions.
     */
    private void stampSourceMetadata(List<Document> pages, String filename, String absolutePath) {
        for (int i = 0; i < pages.size(); i++) {
            Map<String, Object> metadata = pages.get(i).getMetadata();
            metadata.put(DocumentMetadataKeys.SOURCE, filename);
            metadata.put(DocumentMetadataKeys.PAGE, i + 1);
            metadata.put(DocumentMetadataKeys.ABSOLUTE_PATH, absolutePath);
        }
    }

    /** Result of a directory ingestion: how much was added, and which files (if any) failed. */
    public record IngestResult(
            String directory,
            int filesProcessed,
            int chunksAdded,
            List<String> ingested,
            List<Map<String, String>> failed) {
    }
}
