package io.jmix.ai.backend.checks;

import io.jmix.ai.backend.entity.CheckRunStatus;
import org.springframework.context.ApplicationEvent;
import org.springframework.lang.Nullable;

import java.util.UUID;

public class CheckRunCompletedUiEvent extends ApplicationEvent {

    private final UUID checkRunId;
    private final CheckRunStatus status;
    @Nullable
    private final Double score;
    @Nullable
    private final String failureReason;

    public CheckRunCompletedUiEvent(Object source,
                                    UUID checkRunId,
                                    CheckRunStatus status,
                                    @Nullable Double score,
                                    @Nullable String failureReason) {
        super(source);
        this.checkRunId = checkRunId;
        this.status = status;
        this.score = score;
        this.failureReason = failureReason;
    }

    public UUID getCheckRunId() {
        return checkRunId;
    }

    public CheckRunStatus getStatus() {
        return status;
    }

    @Nullable
    public Double getScore() {
        return score;
    }

    @Nullable
    public String getFailureReason() {
        return failureReason;
    }
}
