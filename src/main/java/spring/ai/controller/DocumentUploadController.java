package spring.ai.controller;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import spring.ai.model.DocumentMetadataKeys;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Accepts PDF uploads (one or more, at any time) and adds their chunked,
 * embedded content to the shared in-memory {@link SimpleVectorStore}, making
 * it immediately available to {@code /chat/rag} without an app restart.
 */
@RestController
public class DocumentUploadController {

    private static final String PDF_EXTENSION = ".pdf";

    private final SimpleVectorStore vectorStore;

    public DocumentUploadController(SimpleVectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @PostMapping("/documents")
    public ResponseEntity<Map<String, Object>> uploadDocuments(@RequestParam("files") List<MultipartFile> files) {
        TextSplitter textSplitter = new TokenTextSplitter();
        List<Document> allChunks = new ArrayList<>();

        for (MultipartFile file : files) {
            if (file.isEmpty()) {
                continue;
            }

            String filename = file.getOriginalFilename();

            if (!isPdf(file, filename)) {
                return badRequest("Only PDF files are supported: " + filename);
            }

            try {
                allChunks.addAll(readAndSplit(file, filename, textSplitter));
            } catch (IOException e) {
                return ResponseEntity
                        .status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(Map.of("error", "Failed to read file " + filename + ": " + e.getMessage()));
            }
        }

        if (allChunks.isEmpty()) {
            return badRequest("No valid PDF files provided");
        }

        vectorStore.add(allChunks);

        return ResponseEntity.ok(Map.of(
                "message", "Documents added to vector store",
                "filesProcessed", files.size(),
                "chunksAdded", allChunks.size()
        ));
    }

    private boolean isPdf(MultipartFile file, String filename) {
        return MediaType.APPLICATION_PDF_VALUE.equals(file.getContentType())
                || (filename != null && filename.toLowerCase().endsWith(PDF_EXTENSION));
    }

    /**
     * Reads a single PDF (one {@link Document} per physical page), stamps
     * each page with source/page metadata for later citation, and splits it
     * into embeddable chunks.
     */
    private List<Document> readAndSplit(MultipartFile file, String filename, TextSplitter textSplitter) throws IOException {
        ByteArrayResource resource = new ByteArrayResource(file.getBytes()) {
            @Override
            public String getFilename() {
                return filename;
            }
        };

        PdfDocumentReaderConfig config = PdfDocumentReaderConfig
                .builder()
                .withPagesPerDocument(1)
                .build();

        List<Document> pages = new PagePdfDocumentReader(resource, config).get();
        stampSourceMetadata(pages, filename);

        return textSplitter.apply(pages);
    }

    /**
     * Stamps our own source/page metadata explicitly (rather than relying on
     * whatever keys the reader may or may not set) so citations are reliable
     * downstream when answering RAG questions.
     */
    private void stampSourceMetadata(List<Document> pages, String filename) {
        for (int i = 0; i < pages.size(); i++) {
            Map<String, Object> metadata = pages.get(i).getMetadata();
            metadata.put(DocumentMetadataKeys.SOURCE, filename);
            metadata.put(DocumentMetadataKeys.PAGE, i + 1);
        }
    }

    private ResponseEntity<Map<String, Object>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }
}
