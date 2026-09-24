package com.jannetai.backend.entity;

import com.jannetai.backend.entity.enums.BudgetApprovalStatus;
import com.jannetai.backend.entity.enums.ConfidenceLevel;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Estimated repair cost range and resolution time per complaint - planning
 * estimates only, never a real financial transaction. Maps onto
 * database/migrations/V9__create_budget.sql.
 */
@Entity
@Table(name = "budget")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Budget {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "budget_id")
    private Long budgetId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "complaint_id", nullable = false)
    private Complaint complaint;

    @Column(name = "estimated_cost_min", nullable = false, precision = 10, scale = 2)
    private BigDecimal estimatedCostMin;

    @Column(name = "estimated_cost_max", nullable = false, precision = 10, scale = 2)
    private BigDecimal estimatedCostMax;

    @Column(name = "estimated_resolution_days", nullable = false)
    private Integer estimatedResolutionDays;

    @Enumerated(EnumType.STRING)
    @Column(name = "confidence_level", nullable = false, length = 20)
    private ConfidenceLevel confidenceLevel;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approved_by")
    private User approvedBy;

    /** Gap-backlog Patch 15 (V22). */
    @Enumerated(EnumType.STRING)
    @Column(name = "approval_status", nullable = false, length = 20)
    @Builder.Default
    private BudgetApprovalStatus approvalStatus = BudgetApprovalStatus.PENDING;

    /** Gap-backlog Patch 15 (V22) - null for approvals made before V22 (time never recorded) and for PENDING/REJECTED. */
    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
