package com.jannetai.backend.repository;

import com.jannetai.backend.entity.Prediction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Phase 3 skeleton (plain CRUD access only), extended Phase 10.
 */
@Repository
public interface PredictionRepository extends JpaRepository<Prediction, Long> {

    /**
     * Phase 10 (service.complaint.PriorityBudgetPredictionService): the
     * "most recent attempt is authoritative" reading the V8 migration's
     * own header comment documents for this intentionally non-unique,
     * append-only table. Ordered by {@code createdAt} then
     * {@code predictionId}, both descending, since two rows inserted in
     * the same transaction (e.g. classify's row, immediately followed by
     * this same phase's severity/budget update) can share the same
     * timestamp at this column's precision.
     */
    Optional<Prediction> findFirstByComplaint_ComplaintIdOrderByCreatedAtDescPredictionIdDesc(Long complaintId);

    /** Remaining-gaps item 12: recent predictions for the persistent model-monitoring summary. */
    java.util.List<Prediction> findByCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
            java.time.LocalDateTime since, org.springframework.data.domain.Pageable pageable);
}
