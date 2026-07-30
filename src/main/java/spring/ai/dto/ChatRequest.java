package spring.ai.dto;

/**
 * Request body for {@code POST /chat} and {@code POST /chat/rag}.
 */
public record ChatRequest(String q) {
}
