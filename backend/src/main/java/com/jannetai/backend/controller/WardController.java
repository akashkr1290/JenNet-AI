package com.jannetai.backend.controller;

import com.jannetai.backend.dto.user.WardResponse;
import com.jannetai.backend.service.WardService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Phase 5, Citizen Module (SRS 15.1). Read-only reference-data lookup so
 * the profile-update ward picker has something real to call against.
 *
 * Authenticated, not public: SRS 18 states "All endpoints except
 * registration, login, and OTP verification require a valid JWT" - ward
 * data isn't one of those three named exceptions, so it stays behind auth
 * like everything else, consistent with SecurityConfig's documented
 * fail-closed default.
 */
@RestController
@RequestMapping("/api/v1/wards")
@RequiredArgsConstructor
@Tag(name = "Wards", description = "Read-only ward reference data")
public class WardController {

    private final WardService wardService;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public List<WardResponse> listActiveWards() {
        return wardService.listActiveWards();
    }

    @GetMapping("/{wardId}")
    @PreAuthorize("isAuthenticated()")
    public WardResponse getWard(@PathVariable Long wardId) {
        return wardService.getActiveWard(wardId);
    }
}
