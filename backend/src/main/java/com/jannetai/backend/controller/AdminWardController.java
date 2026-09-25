package com.jannetai.backend.controller;

import com.jannetai.backend.dto.ward.ActiveFlagRequest;
import com.jannetai.backend.dto.ward.AdminWardResponse;
import com.jannetai.backend.dto.ward.WardUpsertRequest;
import com.jannetai.backend.security.UserPrincipal;
import com.jannetai.backend.service.admin.WardAdminService;
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

/** Audit GAP-020 (SRS 15.11): Admin ward/zone configuration. See {@link WardAdminService}. */
@RestController
@RequestMapping("/api/v1/admin/wards")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
@Tag(name = "Admin - Wards", description = "Ward/zone configuration with boundary validation (SRS 15.11)")
public class AdminWardController {

    private final WardAdminService wardAdminService;

    @GetMapping
    public List<AdminWardResponse> list() {
        return wardAdminService.listAll();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AdminWardResponse create(@AuthenticationPrincipal UserPrincipal principal,
                                    @Valid @RequestBody WardUpsertRequest request) {
        return wardAdminService.create(principal.getUser(), request);
    }

    @PutMapping("/{id}")
    public AdminWardResponse update(@AuthenticationPrincipal UserPrincipal principal, @PathVariable Long id,
                                    @Valid @RequestBody WardUpsertRequest request) {
        return wardAdminService.update(principal.getUser(), id, request);
    }

    @PatchMapping("/{id}/status")
    public AdminWardResponse setStatus(@AuthenticationPrincipal UserPrincipal principal, @PathVariable Long id,
                                       @Valid @RequestBody ActiveFlagRequest request) {
        return wardAdminService.setActive(principal.getUser(), id, request.active());
    }
}
