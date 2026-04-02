package io.jmix.ai.backend.retrieval;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jmix.ai.backend.parameters.ParametersReader;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AbstractMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.document.Document;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static io.jmix.ai.backend.retrieval.Utils.getUrlOrSource;

@Component
public class Reranker {

    static final String DEFAULT_MODEL = "gpt-5-nano";
    static final double DEFAULT_TEMPERATURE = 1.0;
    static final int DEFAULT_MAX_DOCUMENT_CHARS = 3000;

    private static final String SYSTEM_PROMPT = """
            You are a document reranker for retrieval augmented generation.
            Score every candidate document for how useful it is for answering the user's query.
            Return one score for every candidate document.
            Scores must be numbers in the range [0, 1], where:
            - 1.0 means directly answers the query with strong supporting detail
            - 0.5 means partially relevant or useful background
            - 0.0 means irrelevant or misleading for this query
            Prefer precision over recall.
            Return all candidates, even weak ones.
            """;

    private static final BeanOutputConverter<RerankResponse> OUTPUT_CONVERTER =
            new BeanOutputConverter<>(RerankResponse.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public record Result(Document document, double score) {
    }

    protected record RerankerOptions(String model, double temperature, int maxDocumentChars) {
    }

    record CandidateDocument(int index, String source, String content) {
    }

    record ScoredDocument(
            @Nullable @JsonProperty(required = true, value = "index") Integer index,
            @Nullable @JsonProperty(required = true, value = "score") Double score
    ) {
        @Override
        public String toString() {
            return "{" + index + ": " + score + "}";
        }
    }

    record RerankResponse(
            @Nullable @JsonProperty(required = true, value = "results") List<ScoredDocument> results
    ) {
    }

    private static final Logger log = LoggerFactory.getLogger(Reranker.class);

    private final OpenAiApi openAiApi;

    public Reranker(
            @Value("${spring.ai.openai.api-key:}") String configuredApiKey
    ) {
        String apiKey = StringUtils.defaultIfBlank(configuredApiKey, System.getenv("OPENAI_API_KEY"));
        if (StringUtils.isBlank(apiKey)) {
            throw new IllegalStateException("OPENAI API key is not set (spring.ai.openai.api-key or OPENAI_API_KEY)");
        }
        this.openAiApi = OpenAiApi.builder()
                .apiKey(apiKey)
                .build();
    }

    @Nullable
    public List<Result> rerank(String query, List<Document> documents, int topN, ParametersReader parametersReader) {
        RerankerOptions options;
        try {
            options = readOptions(parametersReader);
        } catch (Exception e) {
            log.error("Failed to read reranker options", e);
            return null;
        }
        List<CandidateDocument> candidates = buildCandidates(documents, options.maxDocumentChars());
        if (candidates.isEmpty()) {
            log.info("No valid documents to rerank");
            return null;
        }

        log.info("Sending rerank request: query={}, model={}, documentsCount={}",
                query, options.model(), candidates.size());

        ChatResponse response;
        try {
            response = buildChatModel(options).call(buildPrompt(query, candidates));
        } catch (Exception e) {
            log.error("Rerank request failed", e);
            return null;
        }

        String content = getContent(response);
        if (StringUtils.isBlank(content)) {
            log.error("Rerank response was empty");
            return null;
        }

        RerankResponse rerankResponse;
        try {
            rerankResponse = OUTPUT_CONVERTER.convert(content);
        } catch (RuntimeException e) {
            log.error("Failed to parse rerank response: {}", content, e);
            return null;
        }

        List<Result> results = mapResults(rerankResponse, candidates, documents, topN);
        if (results == null) {
            return null;
        }

        log.info("Rerank response: {}", rerankResponse.results());
        return results;
    }

    protected ChatModel buildChatModel(RerankerOptions options) {
        OpenAiChatOptions openAiChatOptions = OpenAiChatOptions.builder()
                .model(options.model())
                .temperature(options.temperature())
                .responseFormat(new ResponseFormat(ResponseFormat.Type.JSON_SCHEMA, OUTPUT_CONVERTER.getJsonSchema()))
                .build();

        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(openAiChatOptions)
                .build();
    }

    static RerankerOptions readOptions(ParametersReader parametersReader) {
        String model = StringUtils.defaultIfBlank(
                parametersReader.getString("reranker.model", DEFAULT_MODEL),
                DEFAULT_MODEL
        );
        Double temperature = parametersReader.getDouble("reranker.temperature", DEFAULT_TEMPERATURE);
        Integer maxDocumentChars = parametersReader.getInteger("reranker.maxDocumentChars", DEFAULT_MAX_DOCUMENT_CHARS);

        return new RerankerOptions(
                model,
                temperature != null ? temperature : DEFAULT_TEMPERATURE,
                maxDocumentChars != null && maxDocumentChars > 0 ? maxDocumentChars : DEFAULT_MAX_DOCUMENT_CHARS
        );
    }

    static List<CandidateDocument> buildCandidates(List<Document> documents, int maxDocumentChars) {
        return IntStream.range(0, documents.size())
                .mapToObj(index -> {
                    Document document = documents.get(index);
                    String text = document.getText() != null ? document.getText().trim() : "";
                    if (text.isEmpty()) {
                        return null;
                    }
                    String truncatedText = text.length() > maxDocumentChars
                            ? text.substring(0, maxDocumentChars)
                            : text;
                    String source = getUrlOrSource(document);
                    return new CandidateDocument(index, source, truncatedText);
                })
                .filter(Objects::nonNull)
                .toList();
    }

    private static Prompt buildPrompt(String query, List<CandidateDocument> candidates) {
        return new Prompt(List.of(
                new SystemMessage(SYSTEM_PROMPT),
                new UserMessage(buildUserMessage(query, candidates))
        ));
    }

    private static String buildUserMessage(String query, List<CandidateDocument> candidates) {
        try {
            return """
                    Query:
                    %s

                    Candidate documents:
                    %s
                    """.formatted(query, OBJECT_MAPPER.writeValueAsString(candidates));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize rerank candidates", e);
        }
    }

    @Nullable
    private static List<Result> mapResults(RerankResponse rerankResponse,
                                           List<CandidateDocument> candidates,
                                           List<Document> documents,
                                           int topN) {
        List<ScoredDocument> scoredDocuments = rerankResponse.results();
        if (scoredDocuments == null || scoredDocuments.size() != candidates.size()) {
            log.error("Rerank response did not contain scores for every candidate: expected={}, actual={}",
                    candidates.size(), scoredDocuments == null ? null : scoredDocuments.size());
            return null;
        }

        Map<Integer, CandidateDocument> candidatesByIndex = candidates.stream()
                .collect(Collectors.toMap(CandidateDocument::index, Function.identity()));
        Set<Integer> seenIndices = new java.util.HashSet<>();

        List<Result> results = scoredDocuments.stream()
                .map(scoredDocument -> {
                    if (scoredDocument.index() == null || scoredDocument.score() == null) {
                        return null;
                    }
                    CandidateDocument candidate = candidatesByIndex.get(scoredDocument.index());
                    if (candidate == null || !seenIndices.add(scoredDocument.index())) {
                        return null;
                    }
                    return new Result(documents.get(candidate.index()), clampScore(scoredDocument.score()));
                })
                .toList();

        if (results.stream().anyMatch(Objects::isNull) || seenIndices.size() != candidates.size()) {
            log.error("Rerank response contained invalid or duplicate indices: {}", scoredDocuments);
            return null;
        }

        return results.stream()
                .sorted((a, b) -> Double.compare(b.score(), a.score()))
                .limit(topN)
                .toList();
    }

    private static double clampScore(double score) {
        return Math.max(0.0, Math.min(1.0, score));
    }

    private static @Nullable String getContent(@Nullable ChatResponse chatResponse) {
        return Optional.ofNullable(chatResponse)
                .map(ChatResponse::getResult)
                .map(Generation::getOutput)
                .map(AbstractMessage::getText)
                .orElse(null);
    }
}
