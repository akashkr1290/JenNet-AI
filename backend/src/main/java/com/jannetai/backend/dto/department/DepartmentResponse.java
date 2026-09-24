package com.jannetai.backend.dto.department;

import com.jannetai.backend.entity.Department;

public record DepartmentResponse(
        Long departmentId,
        String name,
        String description,
        Long headUserId,
        Boolean isActive
) {
    public static DepartmentResponse from(Department d) {
        return new DepartmentResponse(
                d.getDepartmentId(),
                d.getName(),
                d.getDescription(),
                d.getHeadUser() != null ? d.getHeadUser().getUserId() : null,
                d.getIsActive()
        );
    }
}
