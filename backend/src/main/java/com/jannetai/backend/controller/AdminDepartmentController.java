package com.jannetai.backend.controller;

import com.jannetai.backend.dto.ward.ActiveFlagRequest;
import com.jannetai.backend.dto.department.DepartmentResponse;
import com.jannetai.backend.dto.department.DepartmentUpsertRequest;
import com.jannetai.backend.security.UserPrincipal;
import com.jannetai.backend.service.admin.DepartmentAdminService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Audit GAP-020 (SRS 15.11): Admin department configuration. See {@link DepartmentAdminService}. */
@RestController
@RequestMapping("/api/v1/admin/departments")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
@Tag(name = "Admin - Departments", description = "Department configuration incl. head assignment (SRS 15.11)")
public class AdminDepartmentController {

    private final DepartmentAdminService departmentAdminService;

    @GetMapping
    public List<DepartmentResponse> list() {
        return departmentAdminService.listAll();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DepartmentResponse create(@AuthenticationPrincipal UserPrincipal principal,
                                    @Valid @RequestBody DepartmentUpsertRequest request) {
        return departmentAdminService.create(principal.getUser(), request);
    }

    @PutMapping("/{id}")
    public DepartmentResponse update(@AuthenticationPrincipal UserPrincipal principal, @PathVariable Long id,
                                    @Valid @RequestBody DepartmentUpsertRequest request) {
        return departmentAdminService.update(principal.getUser(), id, request);
    }

    @PatchMapping("/{id}/status")
    public DepartmentResponse setStatus(@AuthenticationPrincipal UserPrincipal principal, @PathVariable Long id,
                                       @Valid @RequestBody ActiveFlagRequest request) {
        return departmentAdminService.setActive(principal.getUser(), id, request.active());
    }
}
