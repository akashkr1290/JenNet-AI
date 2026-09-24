package com.jannetai.backend.dto.complaint;

import com.jannetai.backend.entity.Budget;
import com.jannetai.backend.entity.enums.BudgetApprovalStatus;
import com.jannetai.backend.entity.enums.ConfidenceLevel;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Gap-backlog Patch 15: predicted budget range, whether approval is required
 * (estimate max above the configured threshold), approval status, approver
 * and approval time. approvedAt is a real recorded timestamp since V22; it is
 * null (not invented) for approvals made before V22 existed.
 */
public record BudgetResponse(
        BigDecimal estimatedCostMin,
        BigDecimal estimatedCostMax,
        Integer estimatedResolutionDays,
        ConfidenceLevel confidenceLevel,
        boolean approvalRequired,
        BudgetApprovalStatus approvalStatus,
        boolean approved,
        String approvedByName,
        LocalDateTime approvedAt
) {
    public static BudgetResponse from(Budget budget, BigDecimal approvalThresholdInr) {
        boolean approvalRequired = budget.getEstimatedCostMax() != null
                && budget.getEstimatedCostMax().compareTo(approvalThresholdInr) > 0;
        BudgetApprovalStatus status = budget.getApprovalStatus() != null
                ? budget.getApprovalStatus() : BudgetApprovalStatus.PENDING;
        return new BudgetResponse(
                budget.getEstimatedCostMin(),
                budget.getEstimatedCostMax(),
                budget.getEstimatedResolutionDays(),
                budget.getConfidenceLevel(),
                approvalRequired,
                status,
                status == BudgetApprovalStatus.APPROVED,
                budget.getApprovedBy() != null ? budget.getApprovedBy().getFullName() : null,
                budget.getApprovedAt());
    }
}
