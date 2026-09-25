package com.jannetai.backend.controller;

import com.jannetai.backend.dto.admin.PlatformStatusResponse;
import com.jannetai.backend.dto.admin.PlatformStatusUpdateRequest;
import com.jannetai.backend.security.UserPrincipal;
import com.jannetai.backend.service.admin.PlatformStatusService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Audit GAP-037 (SRS 15.11): maintenance mode and announcement banner.
 * The GET is public (SecurityConfig) so the app can show the banner on the
 * login screen too; the PUT is Admin-only and is itself never blocked by
 * maintenance mode (MaintenanceModeFilter exempts Admins).
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Platform status", description = "Maintenance mode and announcement banner (SRS 15.11)")
public class PlatformStatusController {

    private final PlatformStatusService platformStatusService;

    @GetMapping("/api/v1/public/platform-status")
    public PlatformStatusResponse current() {
        return platformStatusService.current();
    }

    @GetMapping("/api/v1/admin/platform-status")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public PlatformStatusResponse adminView() {
        return platformStatusService.adminView();
    }

    @PutMapping("/api/v1/admin/platform-status")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public PlatformStatusResponse update(@AuthenticationPrincipal UserPrincipal principal,
                                         @Valid @RequestBody PlatformStatusUpdateRequest request) {
        platformStatusService.update(principal.getUser(), request);
        return platformStatusService.adminView();
    }
}
