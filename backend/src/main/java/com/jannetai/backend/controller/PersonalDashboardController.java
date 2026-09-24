package com.jannetai.backend.controller;

import com.jannetai.backend.dto.dashboard.CitizenDashboardResponse;
import com.jannetai.backend.dto.dashboard.OfficerDashboardResponse;
import com.jannetai.backend.security.UserPrincipal;
import com.jannetai.backend.service.dashboard.PersonalDashboardService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Gap-backlog Patches 08/09 (Sep 2026 strict recheck). Both endpoints take no
 * id parameter at all - they always describe the caller - so they cannot be
 * used to read another user's data. Department-level comparison for heads
 * remains the existing /api/v1/departments/{id}/performance +
 * /api/v1/dashboard/overview endpoints (Phase 13/16), which already enforce
 * department scope; not duplicated here.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Personal Dashboards", description = "Citizen and officer self-dashboards (Gap-backlog Patches 08/09)")
public class PersonalDashboardController {

    private final PersonalDashboardService personalDashboardService;

    @GetMapping("/citizen/dashboard")
    @PreAuthorize("hasRole('CITIZEN')")
    public CitizenDashboardResponse citizenDashboard(@AuthenticationPrincipal UserPrincipal principal) {
        return personalDashboardService.citizenDashboard(principal.getUser());
    }

    @GetMapping("/officer/dashboard")
    @PreAuthorize("hasAnyRole('GOVERNMENT_OFFICER', 'DEPARTMENT_HEAD', 'MAINTENANCE_TEAM')")
    public OfficerDashboardResponse officerDashboard(@AuthenticationPrincipal UserPrincipal principal) {
        return personalDashboardService.officerDashboard(principal.getUser());
    }
}
