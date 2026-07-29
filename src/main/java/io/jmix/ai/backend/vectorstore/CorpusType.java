package io.jmix.ai.backend.vectorstore;

/**
 * Corpus type identifiers (the {@code type} metadata of vector-store chunks) for the corpuses
 * introduced by this feature. Single source of truth shared by the ingesters that produce them,
 * the retrieval tool that queries them and the cache cleanup that scopes by them.
 * <p>
 * The raw {@code docs}/{@code uisamples}/{@code trainings} corpuses keep their identifiers in the
 * respective ingesters' {@code getType()} and are out of scope here.
 */
public final class CorpusType {

    public static final String JAVA_API = "javaapi";
    public static final String JAVA_API_ENRICHED = "javaapi-enriched";
    public static final String DOCS_SNIPPETS = "docs-snippets";
    public static final String UISAMPLES_SNIPPETS = "uisamples-snippets";

    private CorpusType() {
    }
}
