package io.jmix.ai.backend.view.vectorstore;

import io.jmix.ai.backend.entity.JmixVersion;
import io.jmix.ai.backend.entity.VectorStoreEntity;
import io.jmix.ai.backend.vectorstore.VectorStoreRepository;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.view.MessageBundle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class VectorStoreViewTest {

    private final VectorStoreRepository repository = mock(VectorStoreRepository.class);
    private final Notifications notifications = mock(Notifications.class);
    private final MessageBundle messageBundle = mock(MessageBundle.class);
    private final VectorStoreView view = new VectorStoreView();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(view, "vectorStoreRepository", repository);
        ReflectionTestUtils.setField(view, "notifications", notifications);
        ReflectionTestUtils.setField(view, "messageBundle", messageBundle);
    }

    @Test
    void passesSelectedVersionToRepository() {
        VectorStoreEntity selected = entity("""
                {"type":"docs-snippets","source":"search/search-properties.html","jmixVersion":"v2"}
                """);
        List<VectorStoreEntity> expected = List.of(selected);
        when(repository.loadSourceChunks(
                "docs-snippets", "search/search-properties.html", JmixVersion.V2))
                .thenReturn(expected);

        assertThat(view.loadSelectedSourceChunks(selected)).isSameAs(expected);
        verify(repository).loadSourceChunks(
                "docs-snippets", "search/search-properties.html", JmixVersion.V2);
        verifyNoInteractions(notifications);
    }

    @Test
    void requestsExplicitUnversionedScopeWhenVersionIsAbsent() {
        VectorStoreEntity selected = entity("""
                {"type":"trainings","source":"getting-started"}
                """);
        when(repository.loadSourceChunks("trainings", "getting-started", null))
                .thenReturn(List.of(selected));

        assertThat(view.loadSelectedSourceChunks(selected)).containsExactly(selected);
        verify(repository).loadSourceChunks("trainings", "getting-started", null);
        verifyNoInteractions(notifications);
    }

    @Test
    void deletesOnlyChunksResolvedForTheSelectedVersion() {
        VectorStoreEntity selected = entity("""
                {"type":"docs-snippets","source":"search/search-properties.html","jmixVersion":"v2"}
                """);
        selected.setId(UUID.randomUUID());
        VectorStoreEntity sameVersionChunk = entity(selected.getMetadata());
        sameVersionChunk.setId(UUID.randomUUID());
        when(repository.loadSourceChunks(
                "docs-snippets", "search/search-properties.html", JmixVersion.V2))
                .thenReturn(List.of(selected, sameVersionChunk));

        view.vectorStoreDataGridRemoveActionDelegate(List.of(selected));

        verify(repository).deleteIds(List.of(selected.getId(), sameVersionChunk.getId()));
    }

    @Test
    void rejectsMalformedMetadataWithoutQueryingRepository() {
        Notifications.NotificationBuilder notification = mock(Notifications.NotificationBuilder.class);
        when(messageBundle.getMessage("remove.invalidSourceMetadata")).thenReturn("Invalid metadata");
        when(notifications.create("Invalid metadata")).thenReturn(notification);
        when(notification.withType(Notifications.Type.ERROR)).thenReturn(notification);

        assertThat(view.loadSelectedSourceChunks(entity("""
                {"type":"docs-snippets","source":"page.html","jmixVersion":""}
                """))).isEmpty();
        assertThat(view.loadSelectedSourceChunks(entity("""
                {"type":"docs-snippets","source":"page.html","jmixVersion":"v4"}
                """))).isEmpty();
        assertThat(view.loadSelectedSourceChunks(entity("""
                {"type":"docs-snippets","source":" "}
                """))).isEmpty();
        assertThat(view.loadSelectedSourceChunks(entity("""
                {"source":"page.html"}
                """))).isEmpty();
        assertThat(view.loadSelectedSourceChunks(entity("""
                {"type":"docs-snippets","source":"page.html","jmixVersion":2}
                """))).isEmpty();
        assertThat(view.loadSelectedSourceChunks(entity("not-json"))).isEmpty();
        assertThat(view.loadSelectedSourceChunks(entity("null"))).isEmpty();

        verifyNoInteractions(repository);
        verify(notification, times(7)).show();
    }

    private VectorStoreEntity entity(String metadata) {
        VectorStoreEntity entity = new VectorStoreEntity();
        entity.setMetadata(metadata);
        return entity;
    }
}
