package com.learning;


import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;
import org.springframework.ai.ollama.management.ModelManagementOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class AppConfig {

    @Bean
    @Primary
    @ConditionalOnProperty(name = "app.embedding.llm", havingValue = "ollama", matchIfMissing = true)
    public EmbeddingModel embeddingModelOllama(
            @Value("${app.embedding.ollama.model-name:mxbai-embed-large}") String modelName,
            @Value("${app.embedding.ollama.base-url:http://localhost:11434}") String baseUrl) {
        return new OllamaEmbeddingModel(
                OllamaApi.builder().baseUrl(baseUrl).build(),
                OllamaEmbeddingOptions.builder().model(modelName).build(),
                ObservationRegistry.NOOP,
                ModelManagementOptions.defaults()
        );
    }

    @Bean
    @Primary
    @ConditionalOnProperty(name = "app.embedding.llm", havingValue = "openai")
    public EmbeddingModel embeddingModelOpenAi(
            @Value("${app.embedding.openai.api-key:}") String apiKey,
            @Value("${app.embedding.openai.model-name:text-embedding-3-small}") String modelName) {
        return new OpenAiEmbeddingModel(
                OpenAiEmbeddingOptions.builder()
                        .apiKey(apiKey)
                        .model(modelName)
                        .build()
        );
    }

    @Bean
    @Primary
    @ConditionalOnProperty(name = "app.embedding.llm", havingValue = "ollama", matchIfMissing = true)
    public ChatModel chatModelOllama(
            @Value("${app.chat.model-name:mxbai-embed-large}") String modelName,
            @Value("${app.embedding.ollama.base-url:http://localhost:11434}") String baseUrl) {
        return OllamaChatModel.builder()
                .ollamaApi(OllamaApi.builder().baseUrl(baseUrl).build())
                .options(OllamaChatOptions.builder().model(modelName).build())
                .observationRegistry(ObservationRegistry.NOOP)
                .modelManagementOptions(ModelManagementOptions.defaults())
                .build();
    }

    @Bean
    @Primary
    @ConditionalOnProperty(name = "app.embedding.llm", havingValue = "openai")
    public ChatModel chatModelOpenAi(
            @Value("${app.embedding.openai.api-key:}") String apiKey,
            @Value("${app.chat.model-name:text-embedding-3-small}") String modelName) {
        return OpenAiChatModel.builder()
                .options(OpenAiChatOptions.builder()
                        .apiKey(apiKey)
                        .model(modelName)
                        .build())
                .build();
    }

}
