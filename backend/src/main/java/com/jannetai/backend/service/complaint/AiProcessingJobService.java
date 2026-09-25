package com.jannetai.backend.service.complaint;

import com.jannetai.backend.config.AiProcessingProperties;
import com.jannetai.backend.entity.AiProcessingJob;
import com.jannetai.backend.entity.enums.AiJobState;
import com.jannetai.backend.repository.AiProcessingJobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Audit GAP-010: transactional operations on the ai_processing_jobs queue.
 * Every method runs in its own short transaction (REQUIRES_NEW) so queue
 * bookkeeping never depends on - or is rolled back with - the AI attempt.
 */
@Service
@RequiredArgsConstructor
public class AiProcessingJobService {

    private final AiProcessingJobRepository repository;
    private final AiProcessingProperties properties;

    /** Creates the PENDING job for a new complaint; a no-op if one already exists. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void enqueue(Long complaintId) {
        if (repository.existsById(complaintId)) {
            return;
        }
        repository.save(AiProcessingJob.builder()
                .complaintId(complaintId)
                .state(AiJobState.PENDING)
                .attemptCount(0)
                .nextAttemptAt(LocalDateTime.now())
                .build());
    }

    /** @return true when this caller now owns the next attempt (race-free across threads and instances). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claim(Long complaintId) {
        LocalDateTime now = LocalDateTime.now();
        return repository.claim(complaintId, now, now.plusSeconds(properties.getLeaseSeconds())) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public int attemptsMade(Long complaintId) {
        return repository.findById(complaintId).map(AiProcessingJob::getAttemptCount).orElse(0);
    }

    /** @param note optional reason recorded in last_error_code (e.g. NOT_APPLICABLE) */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(Long complaintId, String note) {
        repository.findById(complaintId).ifPresent(job -> {
            job.setState(AiJobState.DONE);
            job.setLeaseExpiresAt(null);
            job.setLastErrorCode(note);
            repository.save(job);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reschedule(Long complaintId, String errorCode, String error, long delaySeconds) {
        repository.findById(complaintId).ifPresent(job -> {
            job.setState(AiJobState.PENDING);
            job.setLeaseExpiresAt(null);
            job.setNextAttemptAt(LocalDateTime.now().plusSeconds(delaySeconds));
            job.setLastErrorCode(truncate(errorCode, 60));
            job.setLastError(truncate(error, 500));
            repository.save(job);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(Long complaintId, String errorCode, String error) {
        repository.findById(complaintId).ifPresent(job -> {
            job.setState(AiJobState.FAILED);
            job.setLeaseExpiresAt(null);
            job.setLastErrorCode(truncate(errorCode, 60));
            job.setLastError(truncate(error, 500));
            repository.save(job);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int releaseExpiredLeases() {
        return repository.releaseExpiredLeases(LocalDateTime.now());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public List<Long> dueComplaintIds() {
        return repository.findDueComplaintIds(AiJobState.PENDING, LocalDateTime.now(),
                PageRequest.of(0, properties.getSweepBatchSize()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public List<Long> orphanedComplaintIds() {
        return repository.findOrphanedComplaintIds(
                LocalDateTime.now().minusSeconds(properties.getOrphanGraceSeconds()),
                PageRequest.of(0, properties.getSweepBatchSize()));
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
