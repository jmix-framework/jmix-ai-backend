package io.jmix.ai.backend.vectorstore;

import io.jmix.ai.backend.entity.VectorStoreEntity;
import io.jmix.core.EntityStates;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionConverter;
import org.springframework.ai.vectorstore.filter.FilterExpressionTextParser;
import org.springframework.ai.vectorstore.pgvector.PgVectorFilterExpressionConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
public class VectorStoreRepository {

    private final JdbcTemplate jdbcTemplate;
    private final EntityStates entityStates;
    private final FilterExpressionConverter filterExpressionConverter;
    private final FilterExpressionTextParser filterExpressionTextParser;

    public VectorStoreRepository(VectorStore vectorStore, EntityStates entityStates) {
        this.entityStates = entityStates;

        Optional<JdbcTemplate> nativeClient = vectorStore.getNativeClient();
        jdbcTemplate = nativeClient.orElseThrow(() -> new IllegalStateException("No native client available"));

        filterExpressionConverter = new PgVectorFilterExpressionConverter();
        filterExpressionTextParser = new FilterExpressionTextParser();
    }

    public List<VectorStoreEntity> loadList(@Nullable String filterString) {
        return loadList(filterString, 0, 0);
    }

    public List<VectorStoreEntity> loadList(@Nullable String filterString, int offset, int limit) {
        Filter.Expression filterExpression = StringUtils.isBlank(filterString) ? null : filterExpressionTextParser.parse(filterString);
        return loadList(filterExpression, offset, limit);
    }

    public List<VectorStoreEntity> loadList(@Nullable Filter.Expression filterExpression, int offset, int limit) {
        String orderBy = "ORDER BY metadata::jsonb ->> 'type', metadata::jsonb ->> 'source'";
        String limits = (offset > 0 ? " OFFSET " + offset : "") + (limit > 0 ? " LIMIT " + limit : "");
        if (filterExpression != null) {
            String jsonPath = filterExpressionConverter.convertExpression(filterExpression);
            return jdbcTemplate.query(
                    "SELECT id, content, metadata FROM vector_store WHERE metadata::jsonb @@ ?::jsonpath " + orderBy + limits,
                    getVsEntityRowMapper(), jsonPath);
        }
        return jdbcTemplate.query(
                "SELECT id, content, metadata FROM vector_store " + orderBy + limits,
                getVsEntityRowMapper());
    }

    public VectorStoreEntity load(UUID id) {
        return jdbcTemplate.queryForObject(
                "SELECT id, content, metadata FROM vector_store WHERE id = ?",
                getVsEntityRowMapper(), id);
    }

    /**
     * Counts documents grouped by the {@code type} and {@code jmixVersion} metadata attributes.
     * Returns rows of [type, jmixVersion (nullable), count].
     */
    public List<Object[]> countByTypeAndVersion() {
        String sql = "SELECT metadata::jsonb ->> 'type' AS type, " +
                "metadata::jsonb ->> 'jmixVersion' AS version, count(*) AS cnt " +
                "FROM vector_store GROUP BY 1, 2 ORDER BY 1, 2";
        return jdbcTemplate.query(sql, (rs, rowNum) ->
                new Object[]{rs.getString("type"), rs.getString("version"), rs.getInt("cnt")});
    }

    /**
     * Counts docs-snippet chunks (the searchable snippet corpus) grouped by topic
     * (first URL path segment after the version) and Jmix version.
     * Returns rows of [topic, jmixVersion, count]. Used for the topic-coverage heatmap.
     */
    public List<Object[]> countSnippetTopicByVersion() {
        String sql = """
                SELECT regexp_replace(
                         split_part(regexp_replace(metadata::jsonb->>'url','^https?://docs\\.jmix\\.io/([0-9]+\\.x/jmix/[0-9.]+/|jmix/)',''),'/',1),
                         '\\.html.*$','') AS topic,
                       metadata::jsonb->>'jmixVersion' AS version,
                       count(*) AS cnt
                FROM vector_store
                WHERE metadata::jsonb->>'type' = 'docs-snippets'
                GROUP BY 1, 2""";
        return jdbcTemplate.query(sql, (rs, rowNum) ->
                new Object[]{rs.getString("topic"), rs.getString("version"), rs.getInt("cnt")});
    }

    /**
     * Average snippet size in approx. tokens (content chars / 4) per documentation topic,
     * over the docs-snippet corpus. Returns rows of [topic, avgTokens, count].
     */
    public List<Object[]> avgSnippetTokensByTopic() {
        String sql = """
                SELECT regexp_replace(
                         split_part(regexp_replace(metadata::jsonb->>'url','^https?://docs\\.jmix\\.io/([0-9]+\\.x/jmix/[0-9.]+/|jmix/)',''),'/',1),
                         '\\.html.*$','') AS topic,
                       round(avg(length(content) / 4.0)) AS avg_tokens,
                       count(*) AS cnt
                FROM vector_store
                WHERE metadata::jsonb->>'type' = 'docs-snippets'
                GROUP BY 1""";
        return jdbcTemplate.query(sql, (rs, rowNum) ->
                new Object[]{rs.getString("topic"), rs.getInt("avg_tokens"), rs.getInt("cnt")});
    }

    /** Approx. token size (content chars / 4) of every docs-snippet chunk, for the size distribution. */
    public List<Integer> snippetTokenSizes() {
        String sql = "SELECT ceil(length(content) / 4.0)::int AS tok " +
                "FROM vector_store WHERE metadata::jsonb->>'type' = 'docs-snippets'";
        return jdbcTemplate.queryForList(sql, Integer.class);
    }

    public int getCount(String filterString) {
        Filter.Expression filterExpression = StringUtils.isBlank(filterString) ? null : filterExpressionTextParser.parse(filterString);

        Long count;
        if (filterExpression != null) {
            String jsonPath = filterExpressionConverter.convertExpression(filterExpression);
            count = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM vector_store WHERE metadata::jsonb @@ ?::jsonpath", Long.class, jsonPath);
        } else {
            count = jdbcTemplate.queryForObject("SELECT count(*) FROM vector_store", Long.class);
        }
        return count == null ? 0 : count.intValue();
    }

    private RowMapper<VectorStoreEntity> getVsEntityRowMapper() {
        return (rs, rowNum) -> {
            VectorStoreEntity entity = new VectorStoreEntity();
            entity.setId(UUID.fromString(rs.getString("id")));
            entity.setContent(rs.getString("content"));
            entity.setMetadata(rs.getString("metadata"));
            entityStates.setNew(entity, false);
            return entity;
        };
    }

    public void delete(UUID id) {
        jdbcTemplate.update("DELETE FROM vector_store WHERE id = ?", id);
    }

    public void delete(Collection<VectorStoreEntity> collection) {
        deleteIds(collection.stream().map(VectorStoreEntity::getId).toList());
    }

    public void deleteIds(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return;
        }
        String placeholders = ids.stream().map(id -> "?").collect(Collectors.joining(","));
        jdbcTemplate.update("DELETE FROM vector_store WHERE id IN (" + placeholders + ")", ids.toArray());
    }

    public void delete(@Nullable String filterString) {
        Filter.Expression filterExpression = StringUtils.isBlank(filterString) ? null : filterExpressionTextParser.parse(filterString);
        if (filterExpression != null) {
            String jsonPath = filterExpressionConverter.convertExpression(filterExpression);
            jdbcTemplate.update("DELETE FROM vector_store WHERE metadata::jsonb @@ ?::jsonpath", jsonPath);
        } else {
            jdbcTemplate.update("DELETE FROM vector_store");
        }
    }

    public void deleteAll() {
        jdbcTemplate.update("DELETE FROM vector_store");
    }
}
