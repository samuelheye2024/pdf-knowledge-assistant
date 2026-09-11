package spring.ai.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import spring.ai.service.IngestionService;

import java.io.IOException;

/**
 * Chat-callable tools, registered as a default tool on the shared
 * {@code ChatClient} in {@link spring.ai.controller.ChatController}. Lets a
 * user ask the assistant in plain language to ingest a folder of PDFs
 * ("please ingest the PDFs in ~/Documents/legal") as an alternative to the
 * "Browse Folder..." button in the sidebar — both paths call the same
 * {@link IngestionService}, so behavior can never diverge between them.
 */
@Component
public class IngestionTools {

    private final IngestionService ingestionService;

    public IngestionTools(IngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @Tool(description = "Ingest every PDF found in a directory on the local filesystem into the "
            + "knowledge base, so its content becomes searchable in PDF Knowledge Assistant mode. "
            + "Files are read directly from disk in place -- never copied or uploaded. Call this "
            + "whenever the user asks to ingest, index, load, or add PDFs or documents from a "
            + "folder or path they name.")
    public String ingestDirectory(
            @ToolParam(description = "Absolute path, or a path starting with ~ for the user's home "
                    + "directory (e.g. ~/Documents/legal), to the directory containing the PDFs to "
                    + "ingest.")
            String path,
            @ToolParam(description = "Whether to also scan subfolders recursively. Defaults to true "
                    + "when omitted.", required = false)
            Boolean recursive) {

        try {
            IngestionService.IngestResult result =
                    ingestionService.ingestDirectory(path, recursive == null || recursive);

            StringBuilder summary = new StringBuilder()
                    .append("Ingested ").append(result.filesProcessed())
                    .append(" PDF(s), ").append(result.chunksAdded())
                    .append(" chunk(s), from ").append(result.directory()).append(".");

            if (!result.ingested().isEmpty()) {
                summary.append(" Files: ").append(String.join(", ", result.ingested())).append(".");
            }
            if (!result.failed().isEmpty()) {
                summary.append(" Failed: ");
                result.failed().forEach(f -> summary
                        .append(f.get("file")).append(" (").append(f.get("error")).append("); "));
            }

            return summary.toString();
        } catch (IllegalArgumentException e) {
            return "Couldn't ingest that folder: " + e.getMessage();
        } catch (IOException e) {
            return "Failed to scan that folder: " + e.getMessage();
        }
    }
}
