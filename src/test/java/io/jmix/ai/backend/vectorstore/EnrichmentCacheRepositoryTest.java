package io.jmix.ai.backend.vectorstore;

import io.jmix.ai.backend.entity.EnrichmentCache;
import io.jmix.core.DataManager;
import io.jmix.data.exception.UniqueConstraintViolationException;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EnrichmentCacheRepositoryTest {

    @Test
    void saveRetriesUpdateWhenAnotherRunWinsInsertRace() {
        DataManager dataManager = mock(DataManager.class);
        EnrichmentCacheRepository repository = spy(new EnrichmentCacheRepository(dataManager));
        EnrichmentCache attempted = new EnrichmentCache();
        attempted.setId(UUID.randomUUID());
        EnrichmentCache concurrent = new EnrichmentCache();
        concurrent.setId(UUID.randomUUID());
        doReturn(Optional.empty(), Optional.of(concurrent))
                .when(repository).find("docs-snippets", "page.html", "v2", "model:p3");
        when(dataManager.create(EnrichmentCache.class)).thenReturn(attempted);
        when(dataManager.save(attempted)).thenThrow(new UniqueConstraintViolationException());

        repository.save("docs-snippets", "page.html", "v2", "model:p3",
                "hash", "content");

        verify(dataManager).save(concurrent);
        assertThat(concurrent.getContentHash()).isEqualTo("hash");
        assertThat(concurrent.getContent()).isEqualTo("content");
    }
}
