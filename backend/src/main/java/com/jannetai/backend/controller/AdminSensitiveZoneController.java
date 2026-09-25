package com.jannetai.backend.controller;

import com.jannetai.backend.dto.ward.ActiveFlagRequest;
import com.jannetai.backend.dto.zone.SensitiveZoneRequest;
import com.jannetai.backend.dto.zone.SensitiveZoneResponse;
import com.jannetai.backend.security.UserPrincipal;
import com.jannetai.backend.service.admin.SensitiveZoneService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Audit GAP-033 (SRS 15.8): Admin-recorded schools/hospitals/high-traffic roads. */
@RestController
@RequestMapping("/api/v1/admin/sensitive-zones")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
@Tag(name = "Admin - Sensitive zones", description = "Location-sensitivity zones for priority weighting (SRS 15.8)")
public class AdminSensitiveZoneController {

    private final SensitiveZoneService sensitiveZoneService;

    @GetMapping
    public List<SensitiveZoneResponse> list() {
        return sensitiveZoneService.listAll();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SensitiveZoneResponse create(@AuthenticationPrincipal UserPrincipal principal,
                                        @Valid @RequestBody SensitiveZoneRequest request) {
        return sensitiveZoneService.create(principal.getUser(), request);
    }

    @PatchMapping("/{id}/status")
    public SensitiveZoneResponse setStatus(@AuthenticationPrincipal UserPrincipal principal, @PathVariable Long id,
                                           @Valid @RequestBody ActiveFlagRequest request) {
        return sensitiveZoneService.setActive(principal.getUser(), id, request.active());
    }
}
