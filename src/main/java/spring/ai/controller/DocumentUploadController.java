package spring.ai.controller;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import spring.ai.dto.IngestDirectoryRequest;
import spring.ai.service.IngestionService;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Ingests PDFs into the vector store, given a directory to scan
 * ({@code POST /documents/ingest-directory}). {@code GET /documents/browse}
 * lists a directory's subfolders (plain {@code java.nio.file}, no native UI
 * toolkit involved) so the frontend can render an in-app, click-through
 * folder browser — no OS dialog, no AWT/Swing, no window-manager focus
 * quirks to fight, and no headless-mode footgun.
 *
 * <p>The actual scan-and-embed logic lives in {@link IngestionService},
 * shared with the chat-callable {@code ingestDirectory} tool
 * ({@link spring.ai.tool.IngestionTools}) so "Browse Folder..." and typing
 * "please ingest ~/Documents/legal" in the chat box behave identically.
 * Files are read in place — never copied or moved — so each chunk's
 * metadata can carry the file's real absolute path, which {@code /chat/rag}
 * then surfaces back in its citations. {@code GET /documents/file} streams
 * a cited PDF back on demand (also reading it in place) so those citations
 * can be opened directly from the UI.
 */
@RestController
public class DocumentUploadController {

    private static final String PDF_EXTENSION = ".pdf";

    private final IngestionService ingestionService;

    public DocumentUploadController(IngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    /**
     * Lists a directory's subfolders for the in-app folder browser.
     *
     * <ul>
     *     <li>No params — lists the user's home directory.</li>
     *     <li>{@code ?path=/abs/path} — lists that directory.</li>
     *     <li>{@code ?roots=true} — lists filesystem roots (drive letters on
     *     Windows, {@code /} on macOS/Linux) instead of a single directory;
     *     {@code path} is ignored when this is set.</li>
     * </ul>
     *
     * <p>Hidden and unreadable entries are filtered out. Each entry includes
     * a non-recursive count of PDFs directly inside it, as a hint for which
     * folder to pick.
     */
    @GetMapping("/documents/browse")
    public ResponseEntity<Map<String, Object>> browseDirectory(
            @RequestParam(required = false) String path,
            @RequestParam(required = false, defaultValue = "false") boolean roots) {

        if (roots) {
            List<Map<String, Object>> rootEntries = new ArrayList<>();
            for (Path root : FileSystems.getDefault().getRootDirectories()) {
                rootEntries.add(directoryEntry(root));
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("path", null);
            body.put("parent", null);
            body.put("entries", rootEntries);
            return ResponseEntity.ok(body);
        }

        Path directory = (path == null || path.isBlank())
                ? Path.of(System.getProperty("user.home"))
                : Path.of(path);

        directory = directory.toAbsolutePath().normalize();

        if (!Files.isDirectory(directory)) {
            return badRequest("Not a directory: " + directory);
        }
        if (!Files.isReadable(directory)) {
            return badRequest("Not readable: " + directory);
        }

        List<Map<String, Object>> entries;
        try {
            entries = listSubdirectories(directory);
        } catch (IOException e) {
            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to list " + directory + ": " + e.getMessage()));
        }

        Path parent = directory.getParent();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("path", directory.toString());
        body.put("parent", parent != null ? parent.toString() : null);
        body.put("entries", entries);
        return ResponseEntity.ok(body);
    }

    /**
     * Streams a single ingested PDF straight off disk so citations in the UI
     * can be opened directly, at a given page, without ever copying the file
     * anywhere. {@code path} must point at an existing, readable {@code .pdf}
     * file; {@code Content-Disposition: inline} lets the browser's built-in
     * PDF viewer render it (and honor a {@code #page=N} fragment) instead of
     * downloading it.
     *
     * <p>Note: like {@code /documents/browse}, this endpoint trusts any path
     * on disk it's given — acceptable for a personal, localhost-only tool,
     * but it should not be exposed beyond that without adding access checks.
     */
    @GetMapping("/documents/file")
    public ResponseEntity<Resource> serveFile(@RequestParam String path) {
        if (path == null || path.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        Path file = Path.of(path).toAbsolutePath().normalize();

        if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
            return ResponseEntity.notFound().build();
        }
        if (!file.getFileName().toString().toLowerCase().endsWith(PDF_EXTENSION)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        Resource resource = new FileSystemResource(file);

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + file.getFileName() + "\"")
                .body(resource);
    }

    private List<Map<String, Object>> listSubdirectories(Path directory) throws IOException {
        try (Stream<Path> stream = Files.list(directory)) {
            return stream
                    .filter(this::isBrowsableDirectory)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase()))
                    .map(this::directoryEntry)
                    .toList();
        }
    }

    private boolean isBrowsableDirectory(Path path) {
        try {
            return Files.isDirectory(path) && Files.isReadable(path) && !Files.isHidden(path);
        } catch (IOException e) {
            return false;
        }
    }

    private Map<String, Object> directoryEntry(Path dir) {
        String name = dir.getFileName() != null ? dir.getFileName().toString() : dir.toString();

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", name);
        entry.put("path", dir.toAbsolutePath().normalize().toString());
        entry.put("pdfCount", countPdfsDirectlyIn(dir));
        return entry;
    }

    private int countPdfsDirectlyIn(Path dir) {
        try (Stream<Path> stream = Files.list(dir)) {
            return (int) stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(PDF_EXTENSION))
                    .count();
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * Reads every PDF found under {@code request.directory()} straight off
     * disk (recursing into subfolders unless {@code recursive: false} is
     * given) and adds their chunks to the vector store, via
     * {@link IngestionService} — the same logic the {@code ingestDirectory}
     * chat tool uses.
     */
    @PostMapping("/documents/ingest-directory")
    public ResponseEntity<Map<String, Object>> ingestDirectory(@RequestBody IngestDirectoryRequest request) {
        if (request == null || request.directory() == null || request.directory().isBlank()) {
            return badRequest("A non-blank 'directory' path is required");
        }

        IngestionService.IngestResult result;
        try {
            result = ingestionService.ingestDirectory(request.directory(), request.recursiveOrDefault());
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (IOException e) {
            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to scan directory: " + e.getMessage()));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "Documents ingested from directory");
        body.put("directory", result.directory());
        body.put("filesProcessed", result.filesProcessed());
        body.put("chunksAdded", result.chunksAdded());
        body.put("ingested", result.ingested());
        if (!result.failed().isEmpty()) {
            body.put("failed", result.failed());
        }

        return ResponseEntity.ok(body);
    }

    private ResponseEntity<Map<String, Object>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }
}
