package spring.ai.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import spring.ai.dto.IngestUrlRequest;
import spring.ai.service.IngestionService;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ingests a PDF from a public URL into the vector store
 * ({@code POST /documents/ingest-url}). The actual download-and-embed logic
 * lives in {@link IngestionService}, shared with the chat-callable
 * {@code ingestUrl} tool ({@link spring.ai.tool.IngestionTools}) so pasting
 * a link in the sidebar and asking the assistant to ingest one in chat
 * behave identically.
 */
@RestController
public class DocumentIngestionController {

    private final IngestionService ingestionService;

    public DocumentIngestionController(IngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    /**
     * Downloads the PDF at {@code request.url()} and adds it to the vector
     * store, via {@link IngestionService#ingestUrl}. This works for a
     * caller on any machine, since the server fetches the URL itself
     * rather than reading a local path.
     */
    @PostMapping("/documents/ingest-url")
    public ResponseEntity<Map<String, Object>> ingestUrl(@RequestBody IngestUrlRequest request) {
        if (request == null || request.url() == null || request.url().isBlank()) {
            return badRequest("A non-blank 'url' is required");
        }

        IngestionService.UrlIngestResult result;
        try {
            result = ingestionService.ingestUrl(request.url());
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (IOException e) {
            return ResponseEntity
                    .status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", "Failed to fetch " + request.url() + ": " + e.getMessage()));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "Document ingested from URL");
        body.put("url", result.url());
        body.put("file", result.file());
        body.put("chunksAdded", result.chunksAdded());

        return ResponseEntity.ok(body);
    }

    private ResponseEntity<Map<String, Object>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }
}
