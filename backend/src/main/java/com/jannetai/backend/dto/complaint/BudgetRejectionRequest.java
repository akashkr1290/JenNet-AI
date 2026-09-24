package com.jannetai.backend.dto.complaint;

import jakarta.validation.constraints.Size;

/** Gap-backlog Patch 15: optional reason recorded in the audit log. */
public record BudgetRejectionRequest(@Size(max = 500) String note) {
}
