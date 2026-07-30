package spring.ai.controller;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import spring.ai.model.DocumentMetadataKeys;
import spring.ai.dto.ChatRequest;
import spring.ai.dto.RagChatResponse;
import spring.ai.dto.Source;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Chat-facing endpoints for the assistant.
 *
 * <ul>
 *     <li>{@code POST /chat} — plain chat with the model, no document context.</li>
 *     <li>{@code POST /chat/rag} — chat grounded in the PDFs held in the vector
 *     store, returning the answer together with the (file, page) sources it was
 *     drawn from.</li>
 * </ul>
 *
 * <p>Both endpoints are stateless: each request is answered independently, with
 * no conversation history retained between calls.
 */
@RestController
public class ChatController {

    /** Number of similar chunks retrieved from the vector store per RAG question. */
    private static final int SIMILARITY_TOP_K = 5;

    private static final String RAG_PROMPT = """
            Your task is to answer the questions about Indian Constitution. Use the information from the DOCUMENTS
            section to provide accurate answers. If unsure or if the answer isn't found in the DOCUMENTS section,
            simply state that you don't know the answer.

            QUESTION:
            {input}

            DOCUMENTS:
            {documents}

            """;

    private final ChatClient chatClient;
    private final VectorStore vectorStore;

    public ChatController(ChatClient.Builder chatClientBuilder, VectorStore vectorStore) {
        this.chatClient = chatClientBuilder.build();
        this.vectorStore = vectorStore;
    }

    @PostMapping("/chat")
    public String chat(@RequestBody ChatRequest request) {
        return chatClient
                .prompt()
                .user(request.q())
                .call()
                .content();
    }

    @PostMapping("/chat/rag")
    public RagChatResponse chatWithRag(@RequestBody ChatRequest request) {
        String question = request.q();

        List<Document> retrievedDocuments = retrieveSimilarDocuments(question);

        PromptTemplate promptTemplate = new PromptTemplate(RAG_PROMPT);

        Map<String, Object> promptParams = new HashMap<>();
        promptParams.put("input", question);
        promptParams.put("documents", joinContent(retrievedDocuments));

        String answer = chatClient
                .prompt(promptTemplate.create(promptParams))
                .call()
                .content();

        return new RagChatResponse(answer, extractSources(retrievedDocuments));
    }

    private List<Document> retrieveSimilarDocuments(String question) {
        return vectorStore.similaritySearch(SearchRequest.query(question).withTopK(SIMILARITY_TOP_K));
    }

    private String joinContent(List<Document> documents) {
        return documents.stream()
                .map(document -> document.getContent().toString())
                .collect(Collectors.joining());
    }

    /**
     * Derives (file, page) citations directly from the chunks that were
     * actually retrieved and fed into the prompt, deduped and sorted for
     * display. Because these come straight from retrieval metadata rather
     * than the model's own output, they can't drift from what the model
     * actually saw.
     */
    private List<Source> extractSources(List<Document> documents) {
        return documents.stream()
                .map(this::toSource)
                .distinct()
                .sorted(Comparator.comparing(Source::file)
                        .thenComparing(source -> source.page() == null ? 0 : source.page()))
                .collect(Collectors.toList());
    }

    private Source toSource(Document document) {
        Map<String, Object> metadata = document.getMetadata();

        String file = String.valueOf(metadata.getOrDefault(DocumentMetadataKeys.SOURCE, "unknown"));
        Integer page = metadata.get(DocumentMetadataKeys.PAGE) instanceof Number pageNumber
                ? pageNumber.intValue()
                : null;

        return new Source(file, page);
    }
}
