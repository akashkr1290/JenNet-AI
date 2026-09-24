package com.jannetai.backend.exception;

/**
 * SRS 15.9 Business Rules: "estimates above a configurable threshold
 * require Department Head approval before the complaint can move to In
 * Progress." Thrown by ComplaintService.updateStatus when a complaint's
 * Budget.estimatedCostMax exceeds app.budget.approval-threshold-inr and
 * Budget.approvedBy is still null. Resolved via
 * DepartmentAssignmentService#approveBudget (PATCH
 * .../complaints/{id}/approve-budget, DEPARTMENT_HEAD/ADMIN/SUPER_ADMIN
 * only), after which the ASSIGNED -&gt; IN_PROGRESS transition is
 * permitted.
 */
public class BudgetApprovalRequiredException extends RuntimeException {
    public BudgetApprovalRequiredException(String message) {
        super(message);
    }
}
