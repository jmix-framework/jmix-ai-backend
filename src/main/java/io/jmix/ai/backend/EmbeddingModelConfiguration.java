package io.jmix.ai.backend;

import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class EmbeddingModelConfiguration {

    private static final int TEI_MAX_BATCH_SIZE = 32;

    @Bean
    @Primary
    public EmbeddingModel embeddingModel(
            @Value("${rag.embedding.base-url:${spring.ai.openai.base-url:}}") String embeddingBaseUrl,
            @Value("${rag.embedding.api-key:${spring.ai.openai.api-key:}}") String embeddingApiKeyProperty,
            @Value("${rag.embedding.model:${spring.ai.openai.embedding.options.model:text-embedding-3-small}}") String embeddingModelName
    ) {
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (StringUtils.isBlank(apiKey)) {
            apiKey = embeddingApiKeyProperty;
        }
        if (StringUtils.isBlank(apiKey)) {
            apiKey = "dummy";
        }

        OpenAiApi.Builder apiBuilder = OpenAiApi.builder()
                .apiKey(apiKey);
        if (StringUtils.isNotBlank(embeddingBaseUrl)) {
            apiBuilder.baseUrl(embeddingBaseUrl);
        }

        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                .model(embeddingModelName)
                .build();

        EmbeddingModel delegate = new OpenAiEmbeddingModel(apiBuilder.build(), MetadataMode.NONE, options);
        return new BatchingEmbeddingModel(delegate, TEI_MAX_BATCH_SIZE);
    }
}
