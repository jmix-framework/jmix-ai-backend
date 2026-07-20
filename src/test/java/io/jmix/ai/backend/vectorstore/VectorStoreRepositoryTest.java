package io.jmix.ai.backend.vectorstore;

import io.jmix.ai.backend.entity.JmixVersion;
import io.jmix.ai.backend.entity.VectorStoreEntity;
import io.jmix.core.EntityStates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class VectorStoreRepositoryTest {

    private final VectorStore vectorStore = mock(VectorStore.class);
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final EntityStates entityStates = mock(EntityStates.class);

    private VectorStoreRepository repository;

    @BeforeEach
    void setUp() {
        when(vectorStore.getNativeClient()).thenReturn(Optional.of(jdbcTemplate));
        repository = new VectorStoreRepository(vectorStore, entityStates);
    }

    @ParameterizedTest
    @EnumSource(JmixVersion.class)
    void loadsOnlyChunksOfTheRequestedVersion(JmixVersion version) {
        List<VectorStoreEntity> expected = List.of(new VectorStoreEntity());
        when(jdbcTemplate.query(anyString(), any(RowMapper.class),
                eq("docs-snippets"), eq("search/search-properties.html"), eq(version.getId())))
                .thenReturn(expected);

        List<VectorStoreEntity> result = repository.loadSourceChunks(
                "docs-snippets", "search/search-properties.html", version);

        assertThat(result).isSameAs(expected);
        verify(jdbcTemplate).query(
                eq("SELECT id, content, metadata FROM vector_store " +
                        "WHERE metadata::jsonb ->> 'type' = ? " +
                        "AND metadata::jsonb ->> 'source' = ? " +
                        "AND metadata::jsonb ->> 'jmixVersion' = ?"),
                any(RowMapper.class),
                eq("docs-snippets"), eq("search/search-properties.html"), eq(version.getId()));
    }

    @Test
    void keepsUnversionedChunksSeparateFromVersionedChunks() {
        List<VectorStoreEntity> expected = List.of(new VectorStoreEntity());
        when(jdbcTemplate.query(anyString(), any(RowMapper.class),
                eq("trainings"), eq("getting-started")))
                .thenReturn(expected);

        List<VectorStoreEntity> result = repository.loadSourceChunks(
                "trainings", "getting-started", null);

        assertThat(result).isSameAs(expected);
        verify(jdbcTemplate).query(
                eq("SELECT id, content, metadata FROM vector_store " +
                        "WHERE metadata::jsonb ->> 'type' = ? " +
                        "AND metadata::jsonb ->> 'source' = ? " +
                        "AND metadata::jsonb ->> 'jmixVersion' IS NULL"),
                any(RowMapper.class), eq("trainings"), eq("getting-started"));
    }

    @Test
    void rejectsIncompleteSourceIdentityWithoutQuerying() {
        assertThatThrownBy(() -> repository.loadSourceChunks(" ", "source", JmixVersion.V2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.loadSourceChunks("docs", null, JmixVersion.V2))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(jdbcTemplate);
    }
}
