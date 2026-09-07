package spring.ai.controller;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import spring.ai.dto.IngestDirectoryRequest;
import spring.ai.model.DocumentMetadataKeys;

import java.awt.EventQueue;
import java.awt.FileDialog;
import java.awt.Frame;
import java.awt.Taskbar;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Ingests PDFs into the shared {@link VectorStore} by reading them directly
 * off disk, given a directory to scan ({@code POST /documents/ingest-directory}).
 * {@code POST /documents/browse-dialog} pops a native OS "choose a folder"
 * dialog on the machine running this app and returns the chosen absolute
 * path, so the UI never needs the user to type one.
 *
 * <p>Files are read in place — never copied or moved — so each chunk's
 * metadata can carry the file's real absolute path, which {@code /chat/rag}
 * then surfaces back in its citations.
 *
 * <p>The native dialog only makes sense when this app runs directly on a
 * user's own desktop (as intended here) — it requires a display and will
 * fail if run headless (e.g. in a container or over SSH with no GUI).
 */
@RestController
public class DocumentUploadController {

    private static final String PDF_EXTENSION = ".pdf";

    private final VectorStore vectorStore;

    public DocumentUploadController(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * Opens a native OS folder-picker dialog on the machine running this
     * app and returns the chosen directory's absolute path (or
     * {@code {"cancelled": true}} if the user dismissed it without
     * choosing one). The frontend calls this, then passes the result
     * straight to {@code /documents/ingest-directory}.
     */
    @PostMapping("/documents/browse-dialog")
    public ResponseEntity<Map<String, Object>> browseForDirectory() {
        String[] chosen = new String[1];
        String[] error = new String[1];

        Runnable showDialog = () -> {
            Frame owner = new Frame();
            try {
                // A plain terminal-launched JVM has no Dock activation of its
                // own, so the dialog can open *behind* the browser/terminal
                // with no visible cue. Force it to the front and bounce the
                // Dock icon so it's actually noticeable.
                owner.setAlwaysOnTop(true);

                if (Taskbar.isTaskbarSupported()) {
                    Taskbar taskbar = Taskbar.getTaskbar();
                    if (taskbar.isSupported(Taskbar.Feature.USER_ATTENTION)) {
                        taskbar.requestUserAttention(true, true);
                    }
                }

                FileDialog dialog = new FileDialog(owner, "Select a folder to ingest", FileDialog.LOAD);
                dialog.setMultipleMode(false);
                dialog.setAlwaysOnTop(true);
                dialog.toFront();
                dialog.setVisible(true);

                String dir = dialog.getDirectory();
                String file = dialog.getFile();
                if (dir != null && file != null) {
                    chosen[0] = Path.of(dir, file).toString();
                }
            } catch (Exception e) {
                error[0] = e.getMessage();
            } finally {
                owner.dispose();
            }
        };

        try {
            if (EventQueue.isDispatchThread()) {
                showDialog.run();
            } else {
                EventQueue.invokeAndWait(showDialog);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Interrupted while waiting for folder dialog"));
        } catch (InvocationTargetException e) {
            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to open folder dialog: " + e.getCause()));
        }

        if (error[0] != null) {
            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to open folder dialog: " + error[0]
                            + " (this endpoint requires a display; it won't work headless)"));
        }

        if (chosen[0] == null) {
            return ResponseEntity.ok(Map.of("cancelled", true));
        }

        return ResponseEntity.ok(Map.of("directory", chosen[0]));
    }

    /**
     * Reads every PDF found under {@code request.directory()} straight off
     * disk (recursing into subfolders unless {@code recursive: false} is
     * given) and adds their chunks to the vector store.
     */
    @PostMapping("/documents/ingest-directory")
    public ResponseEntity<Map<String, Object>> ingestDirectory(@RequestBody IngestDirectoryRequest request) {
        if (request == null || request.directory() == null || request.directory().isBlank()) {
            return badRequest("A non-blank 'directory' path is required");
        }

        Path directory = Path.of(request.directory()).toAbsolutePath().normalize();

        if (!Files.isDirectory(directory)) {
            return badRequest("Not a directory: " + directory);
        }

        List<Path> pdfFiles;
        try {
            pdfFiles = findPdfFiles(directory, request.recursiveOrDefault());
        } catch (IOException e) {
            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to scan directory " + directory + ": " + e.getMessage()));
        }

        if (pdfFiles.isEmpty()) {
            return badRequest("No PDF files found in: " + directory);
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

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "Documents ingested from directory");
        body.put("directory", directory.toString());
        body.put("filesProcessed", ingested.size());
        body.put("chunksAdded", allChunks.size());
        body.put("ingested", ingested);
        if (!failed.isEmpty()) {
            body.put("failed", failed);
        }

        return ResponseEntity.ok(body);
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

    private ResponseEntity<Map<String, Object>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }
}
