package com.jannetai.backend.service.complaint;

import com.jannetai.backend.config.AiProcessingProperties;
import com.jannetai.backend.service.AuditService;
import com.jannetai.backend.service.notification.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Audit GAP-010: the submission path never runs AI inline (async), and the
 * retry / hand-over rules of SRS 15.3 ("3 retries then Verification Team").
 */
@ExtendWith(MockitoExtension.class)
class AiProcessingDispatcherTest {

    @Mock private AiProcessingJobService jobs;
    @Mock private AiClassificationService aiClassificationService;
    @Mock private NotificationService notificationService;
    @Mock private AuditService auditService;

    private final List<Runnable> queued = new ArrayList<>();
    private AiProcessingProperties properties;
    private AiProcessingDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        properties = new AiProcessingProperties();
        Executor capturingExecutor = queued::add; // run tasks explicitly in the test
        dispatcher = new AiProcessingDispatcher(jobs, aiClassificationService, notificationService, auditService,
                properties, capturingExecutor);
    }

    private static AiClassificationService.AttemptResult failed(String code) {
        return new AiClassificationService.AttemptResult(AiClassificationService.Outcome.FAILED, code, "boom");
    }

    @Test
    void submitQueuesTheJobAndReturnsWithoutCallingAi() {
        boolean ranInline = dispatcher.submit(7L);

        assertThat(ranInline).isFalse();
        verify(jobs).enqueue(7L);
        assertThat(queued).hasSize(1);
        verify(aiClassificationService, never()).processOnce(anyLong());
    }

    @Test
    void successfulAttemptMarksTheJobDone() {
        when(jobs.claim(7L)).thenReturn(true);
        when(aiClassificationService.processOnce(7L))
                .thenReturn(AiClassificationService.AttemptResult.of(AiClassificationService.Outcome.COMPLETED));

        dispatcher.submit(7L);
        queued.get(0).run();

        verify(jobs).complete(7L, null);
    }

    @Test
    void transientFailureIsRescheduledWithBackoff() {
        when(jobs.claim(7L)).thenReturn(true);
        when(aiClassificationService.processOnce(7L)).thenReturn(failed("AI_SERVICE_UNREACHABLE"));
        when(jobs.attemptsMade(7L)).thenReturn(1);

        dispatcher.runAttempt(7L);

        verify(jobs).reschedule(7L, "AI_SERVICE_UNREACHABLE", "boom", 30L);
        verify(jobs, never()).fail(anyLong(), anyString(), anyString());
        verify(notificationService, never()).notifyManualVerificationRequired(anyLong(), anyString());
    }

    @Test
    void thirdFailedAttemptHandsTheComplaintToTheVerificationTeam() {
        when(jobs.claim(7L)).thenReturn(true);
        when(aiClassificationService.processOnce(7L)).thenReturn(failed("MODEL_TIMEOUT"));
        when(jobs.attemptsMade(7L)).thenReturn(3);

        dispatcher.runAttempt(7L);

        verify(jobs).fail(7L, "MODEL_TIMEOUT", "boom");
        verify(auditService).record(isNull(), eq("AI_PROCESSING_MANUAL_REVIEW_REQUIRED"), eq("COMPLAINT"), eq(7L), anyString());
        verify(notificationService).notifyManualVerificationRequired(7L, "MODEL_TIMEOUT");
    }

    @Test
    void unusableImageGoesStraightToTheVerificationTeamWithoutRetry() {
        when(jobs.claim(7L)).thenReturn(true);
        when(aiClassificationService.processOnce(7L)).thenReturn(failed("UNPROCESSABLE_IMAGE"));
        when(jobs.attemptsMade(7L)).thenReturn(1);

        dispatcher.runAttempt(7L);

        verify(jobs, never()).reschedule(anyLong(), anyString(), anyString(), anyLong());
        verify(notificationService).notifyManualVerificationRequired(7L, "UNPROCESSABLE_IMAGE");
    }

    @Test
    void anUnexpectedExceptionIsTreatedAsARetryableFailure() {
        when(jobs.claim(7L)).thenReturn(true);
        when(aiClassificationService.processOnce(7L)).thenThrow(new IllegalStateException("db hiccup"));
        when(jobs.attemptsMade(7L)).thenReturn(1);

        dispatcher.runAttempt(7L);

        verify(jobs).reschedule(7L, "UNEXPECTED_ERROR", "db hiccup", 30L);
    }

    @Test
    void anAttemptThatLosesTheClaimDoesNothing() {
        when(jobs.claim(7L)).thenReturn(false);

        dispatcher.runAttempt(7L);

        verify(aiClassificationService, never()).processOnce(anyLong());
    }

    @Test
    void aFullPoolLeavesTheJobForTheSweeper() {
        Executor rejecting = task -> { throw new RejectedExecutionException("full"); };
        dispatcher = new AiProcessingDispatcher(jobs, aiClassificationService, notificationService, auditService,
                properties, rejecting);

        assertThat(dispatcher.submit(7L)).isFalse(); // no exception reaches POST /complaints
        verify(jobs).enqueue(7L);
    }

    @Test
    void sweepRequeuesOrphansAndStartsDueJobs() {
        when(jobs.orphanedComplaintIds()).thenReturn(List.of(11L));
        when(jobs.dueComplaintIds()).thenReturn(List.of(11L, 12L));

        dispatcher.sweep();

        verify(jobs).releaseExpiredLeases();
        verify(jobs).enqueue(11L);
        assertThat(queued).hasSize(2);
    }

    @Test
    void synchronousModeRunsTheAttemptBeforeReturning() {
        properties.setAsyncEnabled(false);
        when(jobs.claim(7L)).thenReturn(true);
        when(aiClassificationService.processOnce(7L))
                .thenReturn(AiClassificationService.AttemptResult.of(AiClassificationService.Outcome.COMPLETED));

        assertThat(dispatcher.submit(7L)).isTrue();
        verify(jobs).complete(7L, null);
        assertThat(queued).isEmpty();
    }
}
