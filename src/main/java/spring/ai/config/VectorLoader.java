package spring.ai.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class VectorLoader {

    @Bean
    SimpleVectorStore simpleVectorStore(EmbeddingModel embeddingModel) {
        // Starts empty; populated at runtime via the /documents upload endpoint.
        return new SimpleVectorStore(embeddingModel);
    }
}
