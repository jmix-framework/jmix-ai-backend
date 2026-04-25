package io.jmix.ai.backend.checks;

import io.jmix.ai.backend.entity.CheckRun;
import io.jmix.ai.backend.entity.CheckRunStatus;
import io.jmix.core.DataManager;
import io.jmix.core.Id;
import io.jmix.core.security.CurrentAuthentication;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.flowui.UiEventPublisher;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

@Component
public class CheckRunBackgroundLauncher {

    private static final Logger log = LoggerFactory.getLogger(CheckRunBackgroundLauncher.class);

    private final CheckRunner checkRunner;
    private final DataManager dataManager;
    private final TaskExecutor taskExecutor;
    private final UiEventPublisher uiEventPublisher;
    private final CurrentAuthentication currentAuthentication;
    private final SystemAuthenticator systemAuthenticator;

    public CheckRunBackgroundLauncher(CheckRunner checkRunner,
                                      DataManager dataManager,
                                      TaskExecutor taskExecutor,
                                      UiEventPublisher uiEventPublisher,
                                      CurrentAuthentication currentAuthentication,
                                      SystemAuthenticator systemAuthenticator) {
        this.checkRunner = checkRunner;
        this.dataManager = dataManager;
        this.taskExecutor = taskExecutor;
        this.uiEventPublisher = uiEventPublisher;
        this.currentAuthentication = currentAuthentication;
        this.systemAuthenticator = systemAuthenticator;
    }

    public void launch(Id<CheckRun> checkRunId) {
        String username = currentAuthentication.getUser().getUsername();
        taskExecutor.execute(() -> runInBackground(checkRunId, username));
    }

    private void runInBackground(Id<CheckRun> checkRunId, String username) {
        try {
            systemAuthenticator.runWithUser(username, () -> checkRunner.runChecks(checkRunId));
        } catch (Exception e) {
            log.error("Check run {} failed with unexpected error", checkRunId.getValue(), e);
        } finally {
            publishCompletionEvent(checkRunId, username);
        }
    }

    private void publishCompletionEvent(Id<CheckRun> checkRunId, String username) {
        CheckRun checkRun = systemAuthenticator.withUser(username,
                () -> dataManager.load(checkRunId).optional().orElse(null));
        CheckRunStatus status = checkRun != null && checkRun.getStatus() != null
                ? checkRun.getStatus()
                : CheckRunStatus.FAILED;
        Double score = checkRun != null ? checkRun.getScore() : null;
        String failureReason = checkRun != null ? checkRun.getFailureReason() : null;
        if (status == CheckRunStatus.FAILED && StringUtils.isBlank(failureReason)) {
            failureReason = "Unexpected background execution failure";
        }

        uiEventPublisher.publishEventForUsers(
                new CheckRunCompletedUiEvent(this, (java.util.UUID) checkRunId.getValue(), status, score, failureReason),
                java.util.List.of(username)
        );
    }
}
