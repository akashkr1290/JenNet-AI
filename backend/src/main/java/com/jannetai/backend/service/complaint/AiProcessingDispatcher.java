package com.jannetai.backend.service.complaint;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jannetai.backend.config.AiProcessingProperties;
import com.jannetai.backend.service.AuditService;
import com.jannetai.backend.service.notification.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Audit GAP-010 (SRS 15.3 exception: "retry queue, 3 retries, then the
 * Verification Team"; NFR: submission acknowledged in under 2 s).
 *
 * <p>POST /complaints commits the complaint at AI_PROCESSING and calls
 * {@link #submit}: a PENDING job row is written and the attempt is handed to
 * the bounded {@code aiProcessingExecutor}, so the citizen gets 201 at once.
 *
 * <p>Each attempt ({@link #runAttempt}) first claims the job with a
 * conditional UPDATE (one winner across threads and backend instances), then
 * runs {@link AiClassificationService#processOnce}:
 * <ul>
 *   <li>COMPLETED / NOT_APPLICABLE: job DONE;</li>
 *   <li>FAILED and {@link AiRetryPolicy} allows another attempt: job back to
 *       PENDING with exponential backoff;</li>
 *   <li>FAILED otherwise (retries exhausted, or a non-retryable error such as an
 *       unusable image): job FAILED, audited, and the Verification Team is
 *       alerted. The complaint stays at AI_PROCESSING, which is their queue.</li>
 * </ul>
 *
 * <p>{@link #sweep} (every {@code app.ai-processing.sweep-interval-ms}) returns
 * jobs of crashed workers to PENDING, enqueues orphaned AI_PROCESSING complaints
 * (never enqueued, never classified) and starts every due job.
 */
@Component
public class AiProcessingDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AiProcessingDispatcher.class);

    private final AiProcessingJobService jobs;
    private final AiClassificationService aiClassificationService;
    private final NotificationService notificationService;
    private final AuditService auditService;
    private final AiProcessingProperties properties;
    private final Executor executor;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AiProcessingDispatcher(AiProcessingJobService jobs,
                                  AiClassificationService aiClassificationService,
                                  NotificationService notificationService,
                                  AuditService auditService,
                                  AiProcessingProperties properties,
                                  @Qualifier("aiProcessingExecutor") Executor executor) {
        this.jobs = jobs;
        this.aiClassificationService = aiClassificationService;
        this.notificationService = notificationService;
        this.auditService = auditService;
        this.properties = properties;
        this.executor = executor;
    }

    /**
     * Queues AI processing for a complaint that has already been committed.
     *
     * @return true when the attempt already ran on the calling thread
     *         ({@code app.ai-processing.async-enabled=false}), so the caller may
     *         re-read the complaint to return its routed state
     */
    public boolean submit(Long complaintId) {
        jobs.enqueue(complaintId);
        return start(complaintId);
    }

    private boolean start(Long complaintId) {
        if (!properties.isAsyncEnabled()) {
            runAttempt(complaintId);
            return true;
        }
        try {
            executor.execute(() -> runAttempt(complaintId));
        } catch (RejectedExecutionException e) {
            // The job row is PENDING; the sweeper will start it.
            log.warn("AI processing pool is full - complaint {} will be picked up by the next sweep", complaintId);
        }
        return false;
    }

    /** One attempt; never throws. Package-visible for tests. */
    void runAttempt(Long complaintId) {
        try {
            if (!jobs.claim(complaintId)) {
                return; // not due, already running elsewhere, or finished
            }
            AiClassificationService.AttemptResult result;
            try {
                result = aiClassificationService.processOnce(complaintId);
            } catch (RuntimeException e) {
                log.error("AI processing attempt crashed for complaint {}: {}", complaintId, e.getMessage(), e);
                result = new AiClassificationService.AttemptResult(
                        AiClassificationService.Outcome.FAILED, "UNEXPECTED_ERROR", e.getMessage());
            }

            if (result.outcome() != AiClassificationService.Outcome.FAILED) {
                jobs.complete(complaintId,
                        result.outcome() == AiClassificationService.Outcome.NOT_APPLICABLE ? "NOT_APPLICABLE" : null);
                return;
            }

            int attempts = jobs.attemptsMade(complaintId);
            if (AiRetryPolicy.shouldRetry(result.errorCode(), attempts, properties.getMaxAttempts())) {
                long delay = AiRetryPolicy.backoffSeconds(attempts, properties.getRetryBaseDelaySeconds());
                jobs.reschedule(complaintId, result.errorCode(), result.message(), delay);
                log.info("AI processing for complaint {} failed [{}] (attempt {}/{}); retrying in {} s",
                        complaintId, result.errorCode(), attempts, properties.getMaxAttempts(), delay);
                return;
            }

            jobs.fail(complaintId, result.errorCode(), result.message());
            handToVerificationTeam(complaintId, result.errorCode(), attempts);
        } catch (RuntimeException e) {
            // Queue bookkeeping failed (e.g. database briefly unavailable): the
            // lease expires and the sweeper retries - never propagate to a caller.
            log.error("AI processing bookkeeping failed for complaint {}: {}", complaintId, e.getMessage(), e);
        }
    }

    private void handToVerificationTeam(Long complaintId, String errorCode, int attempts) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("error_code", errorCode == null ? "UNKNOWN" : errorCode);
        details.put("attempts", attempts);
        details.put("retryable", AiRetryPolicy.isRetryable(errorCode));
        auditService.record(null, "AI_PROCESSING_MANUAL_REVIEW_REQUIRED", "COMPLAINT", complaintId, toJson(details));
        log.warn("AI processing for complaint {} gave up after {} attempt(s) [{}] - handed to the Verification Team",
                complaintId, attempts, errorCode);
        try {
            notificationService.notifyManualVerificationRequired(complaintId, errorCode);
        } catch (RuntimeException e) {
            log.error("Could not alert the Verification Team about complaint {}: {}", complaintId, e.getMessage(), e);
        }
    }

    @Scheduled(fixedDelayString = "${app.ai-processing.sweep-interval-ms:60000}",
            initialDelayString = "${app.ai-processing.sweep-initial-delay-ms:30000}")
    public void sweep() {
        try {
            int released = jobs.releaseExpiredLeases();
            if (released > 0) {
                log.warn("Released {} AI processing job(s) whose worker lease expired", released);
            }
            for (Long orphan : jobs.orphanedComplaintIds()) {
                try {
                    jobs.enqueue(orphan);
                    log.info("Queued orphaned AI_PROCESSING complaint {} for AI processing", orphan);
                } catch (DataIntegrityViolationException raced) {
                    // enqueued concurrently by POST /complaints or another instance - fine
                }
            }
            for (Long due : jobs.dueComplaintIds()) {
                start(due);
            }
        } catch (RuntimeException e) {
            log.error("AI processing sweep failed: {}", e.getMessage(), e);
        }
    }

    private String toJson(Map<String, Object> details) {
        try {
            return objectMapper.writeValueAsString(details);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
