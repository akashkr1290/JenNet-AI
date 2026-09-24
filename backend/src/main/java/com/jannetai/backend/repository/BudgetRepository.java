package com.jannetai.backend.repository;

import com.jannetai.backend.entity.Budget;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Phase 3 skeleton (plain CRUD access only), extended Phase 11.
 */
@Repository
public interface BudgetRepository extends JpaRepository<Budget, Long> {

    /**
     * Phase 11 (SRS 15.9 budget-approval-threshold gate). Same "latest
     * row is authoritative" convention PredictionRepository already
     * established (V8's header comment) - PriorityBudgetPredictionService
     * only ever writes one Budget row per complaint today (it runs
     * exactly once, at the VERIFIED transition), but this stays a
     * "latest" lookup rather than an unenforced assumed-unique one, for
     * the same defensive-consistency reason.
     */
    Optional<Budget> findFirstByComplaint_ComplaintIdOrderByCreatedAtDescBudgetIdDesc(Long complaintId);
}
