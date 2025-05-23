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

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;
    private final EntityStates entityStates;
    private final FilterExpressionConverter filterExpressionConverter;
    private final FilterExpressionTextParser filterExpressionTextParser;

    public VectorStoreRepository(VectorStore vectorStore, EntityStates entityStates) {
        this.vectorStore = vectorStore;
        this.entityStates = entityStates;

        Optional<JdbcTemplate> nativeClient = vectorStore.getNativeClient();
        jdbcTemplate = nativeClient.orElseThrow(() -> new IllegalStateException("No native client available"));

        filterExpressionConverter = new PgVectorFilterExpressionConverter();
        filterExpressionTextParser = new FilterExpressionTextParser();
    }

    public List<VectorStoreEntity> loadList(@Nullable String filterString) {
        Filter.Expression filterExpression = StringUtils.isBlank(filterString) ? null : filterExpressionTextParser.parse(filterString);
        return loadList(filterExpression);
    }

    public List<VectorStoreEntity> loadList(@Nullable Filter.Expression filterExpression) {
        String sql;
        if (filterExpression != null) {
            String nativeFilterExpression = this.filterExpressionConverter.convertExpression(filterExpression);
            sql = "SELECT id, content, metadata FROM vector_store WHERE metadata::jsonb @@ '"
                    + nativeFilterExpression + "'::jsonpath";
        } else {
            sql = "SELECT id, content, metadata FROM vector_store";
        }
        return jdbcTemplate.query(sql, getVsEntityRowMapper());
    }

    public VectorStoreEntity load(UUID id) {
        return jdbcTemplate.queryForObject(
                "SELECT id, content, metadata FROM vector_store WHERE id = '" + id + "'",
                getVsEntityRowMapper());
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
        jdbcTemplate.update("DELETE FROM vector_store WHERE id = '" + id + "'");
    }

    public void delete(Collection<VectorStoreEntity> collection) {
        String ids = collection.stream()
                .map(vectorStoreEntity -> "'" + vectorStoreEntity.getId() + "'")
                .collect(Collectors.joining(","));
        jdbcTemplate.update("DELETE FROM vector_store WHERE id IN (" + ids + ")");
    }

    public void deleteAll() {
        jdbcTemplate.update("delete from vector_store");
    }
}
