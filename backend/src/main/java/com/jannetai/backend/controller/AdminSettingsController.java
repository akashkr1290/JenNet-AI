package com.jannetai.backend.controller;

import com.jannetai.backend.dto.admin.SettingResponse;
import com.jannetai.backend.dto.admin.SettingUpdateRequest;
import com.jannetai.backend.security.UserPrincipal;
import com.jannetai.backend.service.admin.PlatformSettingsService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Phase 14 (Admin & Settings Module, SRS 15.15 "Admin-level default
 * thresholds"; 16.3 Admin Settings screen). ADMIN/SUPER_ADMIN only,
 * matching every other Admin-tier endpoint in this codebase
 * (AdminRoutingRuleController, DepartmentController's Department Head
 * tier) - there is no department-scoping concept for platform settings
 * (they are, by definition, platform-wide), so no additional own-scope
 * check is needed beyond the role check itself.
 */
@RestController
@RequestMapping("/api/v1/admin/settings")
@RequiredArgsConstructor
@Tag(name = "Admin Settings", description = "Platform-level default thresholds (Phase 14, SRS 15.15)")
public class AdminSettingsController {

    private final PlatformSettingsService platformSettingsService;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public List<SettingResponse> list() {
        return platformSettingsService.listPlatformSettings();
    }

    @PatchMapping("/{key}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public SettingResponse update(@AuthenticationPrincipal UserPrincipal principal,
                                   @PathVariable String key,
                                   @Valid @RequestBody SettingUpdateRequest request) {
        return platformSettingsService.updateSetting(principal.getUser(), key, request.value());
    }
}
