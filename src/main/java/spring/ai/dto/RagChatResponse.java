package spring.ai.dto;

import java.util.List;

/**
 * Response body for {@code POST /chat/rag}: the model's answer, together with
 * the (file, page) sources the retrieved chunks came from.
 */
public record RagChatResponse(String answer, List<Source> sources) {
}
