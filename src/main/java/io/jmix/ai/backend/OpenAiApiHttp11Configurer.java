package io.jmix.ai.backend;

import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.http.HttpClient;

/**
 * LM Studio works reliably with explicit HTTP/1.1 for both chat streaming and embeddings.
 */
public final class OpenAiApiHttp11Configurer {

    private OpenAiApiHttp11Configurer() {
    }

    public static void apply(OpenAiApi.Builder apiBuilder) {
        HttpClient http11Client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        RestClient.Builder restClientBuilder = RestClient.builder()
                .requestFactory(new JdkClientHttpRequestFactory(http11Client));

        WebClient.Builder webClientBuilder = WebClient.builder()
                .clientConnector(new JdkClientHttpConnector(http11Client));

        apiBuilder.restClientBuilder(restClientBuilder)
                .webClientBuilder(webClientBuilder);
    }
}
