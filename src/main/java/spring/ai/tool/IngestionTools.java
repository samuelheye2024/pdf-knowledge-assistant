package spring.ai.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import spring.ai.service.IngestionService;

import java.io.IOException;

/**
 * Chat-callable tool, made available to {@code /chat/rag} in
 * {@link spring.ai.controller.ChatController}. Lets a user ask the
 * assistant in plain language to ingest a PDF from a URL ("please ingest
 * https://example.com/paper.pdf") as an alternative to pasting the link
 * into the sidebar. Both paths call the same {@link IngestionService}, so
 * behavior can never diverge between them.
 */
@Component
public class IngestionTools {

    /**
     * Prefixed onto every result this tool returns. {@code ingestUrl} is
     * marked {@code returnDirect = true}, so whatever it returns becomes
     * {@code /chat/rag}'s final answer verbatim rather than being sent back
     * to the model -- which means {@link spring.ai.controller.ChatController}
     * can reliably tell "the model just ran the ingestion tool" apart from
     * "the model answered a real question" by checking for this marker,
     * with no dependence on how the model happens to phrase things. A
     * plain, distinctive ASCII sentinel is enough here since it's only ever
     * read and stripped back out server-side, never shown to a user.
     */
    public static final String RESULT_MARKER = "INGESTION TOOL RESULT => ";

    private final IngestionService ingestionService;

    public IngestionTools(IngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @Tool(description = "Ingest a single PDF from a public URL on the internet into the knowledge "
            + "base, so its content becomes searchable in PDF Knowledge Assistant mode. The server "
            + "downloads it directly -- call this whenever the user gives a link to a PDF (e.g. "
            + "'ingest this: https://example.com/paper.pdf').",
            returnDirect = true)
    public String ingestUrl(
            @ToolParam(description = "The public http:// or https:// URL of a PDF to download and ingest.")
            String url) {

        try {
            IngestionService.UrlIngestResult result = ingestionService.ingestUrl(url);
            return RESULT_MARKER + "Ingested the PDF at " + result.url() + ".";
        } catch (IllegalArgumentException e) {
            return RESULT_MARKER + "Couldn't ingest that URL: " + e.getMessage();
        } catch (IOException e) {
            return RESULT_MARKER + "Failed to fetch that URL: " + e.getMessage();
        }
    }
}
