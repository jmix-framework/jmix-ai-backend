package io.jmix.ai.backend.checks;

import io.jmix.ai.backend.entity.CheckRun;
import io.jmix.ai.backend.entity.CheckRunStatus;
import io.jmix.core.DataManager;
import io.jmix.core.FluentLoader;
import io.jmix.core.Id;
import io.jmix.core.security.CurrentAuthentication;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.flowui.UiEventPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CheckRunBackgroundLauncherTest {

    @Mock
    private CheckRunner checkRunner;
    @Mock
    private DataManager dataManager;
    @Mock
    private UiEventPublisher uiEventPublisher;
    @Mock
    private CurrentAuthentication currentAuthentication;
    @Mock
    private UserDetails userDetails;
    @Mock
    private FluentLoader.ById<CheckRun> fluentLoader;
    @Mock
    private SystemAuthenticator systemAuthenticator;

    @Test
    void launchPublishesSuccessEvent() {
        UUID runId = UUID.randomUUID();
        CheckRun checkRun = new CheckRun();
        checkRun.setStatus(CheckRunStatus.SUCCESS);
        checkRun.setScore(0.815);

        when(currentAuthentication.getUser()).thenReturn(userDetails);
        when(userDetails.getUsername()).thenReturn("alice");
        when(dataManager.load(any(Id.class))).thenReturn((FluentLoader.ById) fluentLoader);
        when(fluentLoader.optional()).thenReturn(java.util.Optional.of(checkRun));
        when(systemAuthenticator.withUser(eq("alice"), any())).thenAnswer(invocation ->
                ((SystemAuthenticator.AuthenticatedOperation<?>) invocation.getArgument(1)).call());
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            return null;
        }).when(systemAuthenticator).runWithUser(eq("alice"), any(Runnable.class));

        CheckRunBackgroundLauncher launcher = new CheckRunBackgroundLauncher(
                checkRunner, dataManager, new SyncTaskExecutor(), uiEventPublisher, currentAuthentication, systemAuthenticator
        );

        launcher.launch(Id.of(runId, CheckRun.class));

        ArgumentCaptor<CheckRunCompletedUiEvent> eventCaptor = ArgumentCaptor.forClass(CheckRunCompletedUiEvent.class);
        verify(uiEventPublisher).publishEventForUsers(eventCaptor.capture(), any());

        CheckRunCompletedUiEvent event = eventCaptor.getValue();
        assertThat(event.getCheckRunId()).isEqualTo(runId);
        assertThat(event.getStatus()).isEqualTo(CheckRunStatus.SUCCESS);
        assertThat(event.getScore()).isEqualTo(0.815);
        verify(systemAuthenticator).runWithUser(eq("alice"), any(Runnable.class));
        verify(systemAuthenticator).withUser(eq("alice"), any());
        verify(uiEventPublisher).publishEventForUsers(any(CheckRunCompletedUiEvent.class), eq(java.util.List.of("alice")));
    }

    @Test
    void launchPublishesFailedEventWhenRunnerThrows() {
        UUID runId = UUID.randomUUID();
        CheckRun checkRun = new CheckRun();
        checkRun.setStatus(CheckRunStatus.FAILED);
        checkRun.setFailureReason("Semantic evaluator is not configured");

        when(currentAuthentication.getUser()).thenReturn(userDetails);
        when(userDetails.getUsername()).thenReturn("alice");
        when(dataManager.load(any(Id.class))).thenReturn((FluentLoader.ById) fluentLoader);
        when(fluentLoader.optional()).thenReturn(java.util.Optional.of(checkRun));
        when(systemAuthenticator.withUser(eq("alice"), any())).thenAnswer(invocation ->
                ((SystemAuthenticator.AuthenticatedOperation<?>) invocation.getArgument(1)).call());
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            return null;
        }).when(systemAuthenticator).runWithUser(eq("alice"), any(Runnable.class));
        doThrow(new IllegalStateException("boom")).when(checkRunner).runChecks(any());

        CheckRunBackgroundLauncher launcher = new CheckRunBackgroundLauncher(
                checkRunner, dataManager, new SyncTaskExecutor(), uiEventPublisher, currentAuthentication, systemAuthenticator
        );

        launcher.launch(Id.of(runId, CheckRun.class));

        ArgumentCaptor<CheckRunCompletedUiEvent> eventCaptor = ArgumentCaptor.forClass(CheckRunCompletedUiEvent.class);
        verify(uiEventPublisher).publishEventForUsers(eventCaptor.capture(), org.mockito.ArgumentMatchers.eq(List.of("alice")));

        CheckRunCompletedUiEvent event = eventCaptor.getValue();
        assertThat(event.getCheckRunId()).isEqualTo(runId);
        assertThat(event.getStatus()).isEqualTo(CheckRunStatus.FAILED);
        assertThat(event.getFailureReason()).isEqualTo("Semantic evaluator is not configured");
        verify(systemAuthenticator).runWithUser(eq("alice"), any(Runnable.class));
        verify(systemAuthenticator).withUser(eq("alice"), any());
    }
}
