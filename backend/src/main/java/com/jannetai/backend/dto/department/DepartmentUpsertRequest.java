package com.jannetai.backend.dto.department;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Audit GAP-020 (SRS 15.11, 13.4): create or replace a department.
 * {@code headUserId} must be an ACTIVE DEPARTMENT_HEAD account that already
 * belongs to this department (assign the department on the user first via
 * Admin user management); null clears the head. Not accepted on create,
 * because no account can belong to a department that does not exist yet.
 */
public record DepartmentUpsertRequest(
        @NotBlank @Size(max = 100) String name,
        @Size(max = 300) String description,
        Long headUserId
) {
}
