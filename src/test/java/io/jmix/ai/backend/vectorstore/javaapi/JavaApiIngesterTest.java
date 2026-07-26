package io.jmix.ai.backend.vectorstore.javaapi;

import io.jmix.ai.backend.vectorstore.Snippet;
import io.jmix.ai.backend.vectorstore.VectorStoreRepository;
import io.jmix.core.TimeSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class JavaApiIngesterTest {

    @Mock
    private VectorStore vectorStore;
    @Mock
    private TimeSource timeSource;
    @Mock
    private VectorStoreRepository vectorStoreRepository;
    @Mock
    private RestTemplate restTemplate;

    private JavaApiIngester ingester;

    private static final Snippet CARD = new Snippet(
            "Interface DataManager (io.jmix.core)",
            "Same as UnconstrainedDataManager but performs authorization.",
            "java",
            "public interface DataManager extends UnconstrainedDataManager",
            "https://docs.jmix.io/api/2.8/io/jmix/core/DataManager.html");

    @BeforeEach
    void setUp() {
        ingester = new JavaApiIngester(
                "https://docs.jmix.io/api/2.8", "", "allclasses-index.html", "core", "/impl/,/antlr2/", 0,
                vectorStore, timeSource, vectorStoreRepository, restTemplate);
    }

    private Document cardDocument() {
        return new Document("1", CARD.format(), Map.of(
                "type", "javaapi",
                "source", "io/jmix/core/DataManager.html",
                "sourceHash", "hash1",
                "jmixVersion", "v2"));
    }

    @Test
    void splitToChunks_ProducesDeterministicStampedCards() {
        List<Document> chunks = ingester.splitToChunks(List.of(cardDocument()));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().getText()).isEqualTo(CARD.format());
        assertThat(chunks.getFirst().getMetadata())
                .doesNotContainKey("enriched")
                .containsEntry("generationKey", "javaapi-card-format-version-2026-07-26");
    }

    @Test
    void splitToChunks_UsesFourThousandCharacterTargetWithoutLosingBody() {
        String code = "public void method() {}\n".repeat(500);
        String formatted = new Snippet(CARD.title(), CARD.description(), "java", code, CARD.absoluteUrl()).format();
        Document document = new Document("large", formatted, cardDocument().getMetadata());
        String header = formatted.substring(0, formatted.indexOf("```java\n") + "```java\n".length());

        List<Document> chunks = ingester.splitToChunks(List.of(document));

        assertThat(chunks).hasSizeGreaterThan(1)
                .allSatisfy(chunk -> assertThat(chunk.getText().length()).isLessThanOrEqualTo(4_000));
        String reconstructedBody = chunks.stream()
                .map(Document::getText)
                .map(text -> text.substring(header.length(), text.length() - "\n```".length()))
                .collect(Collectors.joining());
        assertThat(reconstructedBody).isEqualTo(code);
    }

    @Test
    void getVersions_ReturnsOnlyConfiguredVersions() {
        assertThat(ingester.getVersions()).extracting(Enum::name).containsExactly("V2");
    }

    @Test
    void updateAll_SkipsJavaApiWhenNoBaseUrlsAreConfigured() {
        JavaApiIngester unconfiguredIngester = new JavaApiIngester(
                "", "", "allclasses-index.html", "core", "/impl/,/antlr2/", 0,
                vectorStore, timeSource, vectorStoreRepository, restTemplate);

        assertThat(unconfiguredIngester.getVersions()).isEmpty();
        assertThat(unconfiguredIngester.updateAll())
                .isEqualTo("skipped: no Java API base URLs configured");
        verifyNoInteractions(vectorStore, timeSource, vectorStoreRepository);
    }

    @Test
    void blacklistExcludesInternalPackages() {
        assertThat(ingester.isAllowedSource("io/jmix/core/DataManager.html")).isTrue();
        assertThat(ingester.isAllowedSource("io/jmix/core/impl/DataManagerImpl.html")).isFalse();
        assertThat(ingester.isAllowedSource("io/jmix/data/impl/jpql/antlr2/JPA2Parser.html")).isFalse();
        assertThat(ingester.isAllowedSource("io/jmix/reports/ReportRunner.html")).isFalse();
    }

    @Test
    void blacklistExcludesInternalImplementingClassReferences() {
        String source = "io/jmix/core/DataManager.html";

        assertThat(ingester.isAllowedReference(source, "UnconstrainedDataManager.html")).isTrue();
        assertThat(ingester.isAllowedReference(source, "impl/DataManagerImpl.html")).isFalse();
    }

    // the published Javadoc HTML does not render @Internal, so the pages of internal API are
    // excluded by the generated resource list (see internal-blacklist.txt) rather than by content
    @Test
    void internalBlacklistExcludesInternalApiPages() {
        // class-level @Internal type
        assertThat(ingester.isAllowedSource("io/jmix/core/AccessLogger.html")).isFalse();
        // nested-class page of an @Internal type
        assertThat(ingester.isAllowedSource("io/jmix/core/AccessLogger.Nested.html")).isFalse();
        // page in an @Internal package
        assertThat(ingester.isAllowedSource("io/jmix/core/common/event/sys/VoidSubscription.html")).isFalse();
        // public API stays
        assertThat(ingester.isAllowedSource("io/jmix/core/DataManager.html")).isTrue();
        assertThat(ingester.isAllowedSource("io/jmix/core/FetchPlan.html")).isTrue();
    }

    @Test
    void internalBlacklistExcludesInternalReferences() {
        String source = "io/jmix/core/DataManager.html";

        assertThat(ingester.isAllowedReference(source, "AccessLogger.html")).isFalse();
        assertThat(ingester.isAllowedReference(source, "FetchPlan.html")).isTrue();
    }

    // @Internal API the official docs teach to use stays in the corpus (internal-whitelist.txt)
    @Test
    void internalWhitelistOverridesTheBlacklist() {
        JavaApiIngester flowuiIngester = new JavaApiIngester(
                "https://docs.jmix.io/api/2.8", "", "allclasses-index.html", "core,flowui",
                "/impl/,/antlr2/", 0, vectorStore, timeSource, vectorStoreRepository, restTemplate);

        // class-level @Internal, taught by dyn-attr/dynattr-api.html
        assertThat(flowuiIngester.isAllowedSource("io/jmix/core/entity/EntityValues.html")).isTrue();
        // covered by a package-level ban, taught by flow-ui/vc/components/multiValuePicker.html
        assertThat(flowuiIngester.isAllowedSource("io/jmix/flowui/app/multivaluepicker/MultiValueSelectView.html"))
                .isTrue();
        // nested-class page follows its whitelisted outer class
        assertThat(flowuiIngester.isAllowedSource(
                "io/jmix/flowui/app/multivaluepicker/MultiValueSelectView.MultiValueSelectContext.html"))
                .isTrue();
        // a sibling in the same banned package stays banned
        assertThat(flowuiIngester.isAllowedSource("io/jmix/flowui/app/multivaluepicker/MultiValueSelectDialog.html"))
                .isFalse();
        // mentioned in the docs only as a logger name - stays banned
        assertThat(flowuiIngester.isAllowedSource("io/jmix/core/AccessLogger.html")).isFalse();
    }
}
