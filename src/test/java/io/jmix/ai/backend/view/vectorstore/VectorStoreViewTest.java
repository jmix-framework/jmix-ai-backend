package io.jmix.ai.backend.view.vectorstore;

import io.jmix.ai.backend.entity.JmixVersion;
import io.jmix.ai.backend.entity.VectorStoreEntity;
import io.jmix.ai.backend.vectorstore.CorpusType;
import io.jmix.ai.backend.vectorstore.VectorStoreRepository;
import io.jmix.flowui.Dialogs;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.kit.action.Action;
import io.jmix.flowui.model.CollectionLoader;
import io.jmix.flowui.util.RemoveOperation;
import io.jmix.flowui.view.MessageBundle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class VectorStoreViewTest {

    private static final String SOURCE = "search/search-properties.html";

    private final VectorStoreRepository repository = mock(VectorStoreRepository.class);
    private final Notifications notifications = mock(Notifications.class);
    private final MessageBundle messageBundle = mock(MessageBundle.class);
    private final Dialogs dialogs = mock(Dialogs.class);
    private final DataGrid<VectorStoreEntity> vectorStoreDataGrid = mock(DataGrid.class);
    private final CollectionLoader<VectorStoreEntity> vectorStoreDl = mock(CollectionLoader.class);
    private final VectorStoreView view = new VectorStoreView();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(view, "vectorStoreRepository", repository);
        ReflectionTestUtils.setField(view, "notifications", notifications);
        ReflectionTestUtils.setField(view, "messageBundle", messageBundle);
        ReflectionTestUtils.setField(view, "dialogs", dialogs);
        ReflectionTestUtils.setField(view, "vectorStoreDataGrid", vectorStoreDataGrid);
        ReflectionTestUtils.setField(view, "vectorStoreDl", vectorStoreDl);
    }

    @Test
    void passesSelectedVersionToRepository() {
        VectorStoreEntity selected = entity("""
                {"type":"%s","source":"%s","jmixVersion":"v2"}
                """.formatted(CorpusType.DOCS_SNIPPETS, SOURCE));
        List<VectorStoreEntity> expected = List.of(selected);
        when(repository.loadSourceChunks(CorpusType.DOCS_SNIPPETS, SOURCE, JmixVersion.V2))
                .thenReturn(expected);

        assertThat(view.loadSelectedSourceChunks(selected)).isSameAs(expected);
        verify(repository).loadSourceChunks(CorpusType.DOCS_SNIPPETS, SOURCE, JmixVersion.V2);
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
                {"type":"%s","source":"%s","jmixVersion":"v2"}
                """.formatted(CorpusType.DOCS_SNIPPETS, SOURCE));
        selected.setId(UUID.randomUUID());
        VectorStoreEntity sameVersionChunk = entity(selected.getMetadata());
        sameVersionChunk.setId(UUID.randomUUID());
        when(repository.loadSourceChunks(CorpusType.DOCS_SNIPPETS, SOURCE, JmixVersion.V2))
                .thenReturn(List.of(selected, sameVersionChunk));

        view.vectorStoreDataGridRemoveActionDelegate(List.of(selected));

        verify(repository).deleteIds(List.of(selected.getId(), sameVersionChunk.getId()));
    }

    @Test
    void rejectsMalformedMetadataWithoutQueryingRepository() {
        stubInvalidMetadataNotification();

        assertThat(view.loadSelectedSourceChunks(entity("""
                {"type":"%s","source":"page.html","jmixVersion":""}
                """.formatted(CorpusType.DOCS_SNIPPETS)))).isEmpty();
        assertThat(view.loadSelectedSourceChunks(entity("""
                {"type":"%s","source":"page.html","jmixVersion":"v4"}
                """.formatted(CorpusType.DOCS_SNIPPETS)))).isEmpty();
        assertThat(view.loadSelectedSourceChunks(entity("""
                {"type":"%s","source":" "}
                """.formatted(CorpusType.DOCS_SNIPPETS)))).isEmpty();
        assertThat(view.loadSelectedSourceChunks(entity("""
                {"source":"page.html"}
                """))).isEmpty();
        assertThat(view.loadSelectedSourceChunks(entity("""
                {"type":"%s","source":"page.html","jmixVersion":2}
                """.formatted(CorpusType.DOCS_SNIPPETS)))).isEmpty();
        assertThat(view.loadSelectedSourceChunks(entity("not-json"))).isEmpty();
        assertThat(view.loadSelectedSourceChunks(entity("null"))).isEmpty();

        verifyNoInteractions(repository);
        verify(invalidMetadataNotification, times(7)).show();
    }

    @Test
    void beforeRemovePreventsAndSkipsConfirmationWhenSelectionIsInvalid() {
        stubInvalidMetadataNotification();
        when(vectorStoreDataGrid.getSingleSelectedItem()).thenReturn(entity("not-json"));
        RemoveOperation.BeforeActionPerformedEvent<VectorStoreEntity> event =
                mock(RemoveOperation.BeforeActionPerformedEvent.class);

        view.vectorStoreDataGridRemoveActionBeforeActionPerformedHandler(event);

        verify(event).preventAction();
        verifyNoInteractions(dialogs);
        verify(repository, never()).deleteIds(any());
    }

    @Test
    void beforeRemoveLetsSingleChunkDeletionProceedWithoutConfirmation() {
        VectorStoreEntity selected = entity("""
                {"type":"%s","source":"%s","jmixVersion":"v2"}
                """.formatted(CorpusType.DOCS_SNIPPETS, SOURCE));
        when(vectorStoreDataGrid.getSingleSelectedItem()).thenReturn(selected);
        when(repository.loadSourceChunks(CorpusType.DOCS_SNIPPETS, SOURCE, JmixVersion.V2))
                .thenReturn(List.of(selected));
        RemoveOperation.BeforeActionPerformedEvent<VectorStoreEntity> event =
                mock(RemoveOperation.BeforeActionPerformedEvent.class);

        view.vectorStoreDataGridRemoveActionBeforeActionPerformedHandler(event);

        verify(event, never()).preventAction();
        verifyNoInteractions(dialogs);
    }

    @Test
    void beforeRemovePreventsAndConfirmsWhenSourceSpansMultipleChunks() {
        Dialogs.OptionDialogBuilder dialogBuilder = mock(Dialogs.OptionDialogBuilder.class);
        when(dialogs.createOptionDialog()).thenReturn(dialogBuilder);
        when(dialogBuilder.withHeader(anyString())).thenReturn(dialogBuilder);
        when(dialogBuilder.withText(anyString())).thenReturn(dialogBuilder);
        when(dialogBuilder.withActions(any(Action[].class))).thenReturn(dialogBuilder);
        VectorStoreEntity selected = entity("""
                {"type":"%s","source":"%s","jmixVersion":"v2"}
                """.formatted(CorpusType.DOCS_SNIPPETS, SOURCE));
        selected.setId(UUID.randomUUID());
        VectorStoreEntity sameVersionChunk = entity(selected.getMetadata());
        sameVersionChunk.setId(UUID.randomUUID());
        when(vectorStoreDataGrid.getSingleSelectedItem()).thenReturn(selected);
        when(repository.loadSourceChunks(CorpusType.DOCS_SNIPPETS, SOURCE, JmixVersion.V2))
                .thenReturn(List.of(selected, sameVersionChunk));
        RemoveOperation.BeforeActionPerformedEvent<VectorStoreEntity> event =
                mock(RemoveOperation.BeforeActionPerformedEvent.class);

        view.vectorStoreDataGridRemoveActionBeforeActionPerformedHandler(event);

        verify(event).preventAction();
        verify(dialogBuilder).open();
        // deletion only happens if the user confirms the dialog, which is not triggered here
        verify(repository, never()).deleteIds(any());
    }

    private Notifications.NotificationBuilder invalidMetadataNotification;

    private void stubInvalidMetadataNotification() {
        invalidMetadataNotification = mock(Notifications.NotificationBuilder.class);
        when(messageBundle.getMessage("remove.invalidSourceMetadata")).thenReturn("Invalid metadata");
        when(notifications.create("Invalid metadata")).thenReturn(invalidMetadataNotification);
        when(invalidMetadataNotification.withType(Notifications.Type.ERROR))
                .thenReturn(invalidMetadataNotification);
    }

    private VectorStoreEntity entity(String metadata) {
        VectorStoreEntity entity = new VectorStoreEntity();
        entity.setMetadata(metadata);
        return entity;
    }
}
