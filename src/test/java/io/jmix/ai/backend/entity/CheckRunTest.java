package io.jmix.ai.backend.entity;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CheckRunTest {

    @Test
    void durationTextUsesStoredDuration() {
        CheckRun checkRun = new CheckRun();
        checkRun.setDurationMs(509_000L);

        assertEquals("8m 29s", checkRun.getDurationText());
    }

    @Test
    void durationTextUsesLiveRunningDurationWhenStoredValueIsMissing() {
        CheckRun checkRun = new CheckRun();
        checkRun.setStatus(CheckRunStatus.RUNNING);
        checkRun.setCreatedDate(OffsetDateTime.now().minusSeconds(65));

        assertEquals("1m 05s", checkRun.getDurationText());
    }

    @Test
    void durationTextIsNullWithoutDurationData() {
        CheckRun checkRun = new CheckRun();

        assertNull(checkRun.getDurationText());
    }
}
