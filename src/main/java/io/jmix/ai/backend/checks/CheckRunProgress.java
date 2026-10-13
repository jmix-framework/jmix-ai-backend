package io.jmix.ai.backend.checks;

import io.jmix.ai.backend.entity.Check;

@FunctionalInterface
public interface CheckRunProgress {

    CheckRunProgress NONE = (ignoredCheck, ignoredCompleted, ignoredTotal) -> {
    };

    void checkCompleted(Check check, int completed, int total) throws InterruptedException;
}
