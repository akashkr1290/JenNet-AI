package com.jannetai.backend.repository;

import com.jannetai.backend.entity.AiProcessingJob;
import com.jannetai.backend.entity.enums.AiJobState;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/** Audit GAP-010: queue operations for {@link AiProcessingJob}. */
public interface AiProcessingJobRepository extends JpaRepository<AiProcessingJob, Long> {

    /**
     * Atomically claims a due PENDING job: exactly one caller (thread or
     * backend instance) gets 1, every other concurrent caller gets 0.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update AiProcessingJob j set j.state = com.jannetai.backend.entity.enums.AiJobState.IN_PROGRESS, "
            + "j.attemptCount = j.attemptCount + 1, j.leaseExpiresAt = :leaseUntil "
            + "where j.complaintId = :complaintId "
            + "and j.state = com.jannetai.backend.entity.enums.AiJobState.PENDING and j.nextAttemptAt <= :now")
    int claim(@Param("complaintId") Long complaintId, @Param("now") LocalDateTime now,
              @Param("leaseUntil") LocalDateTime leaseUntil);

    /** Returns jobs whose worker died mid-attempt to PENDING (their attempt still counts). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update AiProcessingJob j set j.state = com.jannetai.backend.entity.enums.AiJobState.PENDING, "
            + "j.leaseExpiresAt = null, j.nextAttemptAt = :now, j.lastErrorCode = 'WORKER_LEASE_EXPIRED' "
            + "where j.state = com.jannetai.backend.entity.enums.AiJobState.IN_PROGRESS and j.leaseExpiresAt < :now")
    int releaseExpiredLeases(@Param("now") LocalDateTime now);

    @Query("select j.complaintId from AiProcessingJob j where j.state = :state and j.nextAttemptAt <= :now "
            + "order by j.nextAttemptAt asc")
    List<Long> findDueComplaintIds(@Param("state") AiJobState state, @Param("now") LocalDateTime now, Pageable page);

    /**
     * Complaints parked at AI_PROCESSING that never got a job and never got a
     * prediction - submitted while the backend crashed between commit and
     * enqueue, or before this queue existed and whose single inline attempt
     * failed. Complaints that were classified and parked for manual review
     * have a prediction and are NOT returned.
     */
    @Query("select c.complaintId from Complaint c where c.status = com.jannetai.backend.entity.enums.ComplaintStatus.AI_PROCESSING "
            + "and c.createdAt < :cutoff "
            + "and not exists (select j.complaintId from AiProcessingJob j where j.complaintId = c.complaintId) "
            + "and not exists (select p.predictionId from Prediction p where p.complaint = c)")
    List<Long> findOrphanedComplaintIds(@Param("cutoff") LocalDateTime cutoff, Pageable page);
}
