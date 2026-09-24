package com.jannetai.backend.controller;

import com.jannetai.backend.dto.auth.UserProfileResponse;
import com.jannetai.backend.dto.settings.PersonalSettingsResponse;
import com.jannetai.backend.dto.settings.PersonalSettingsUpdateRequest;
import com.jannetai.backend.dto.user.UpdateProfileRequest;
import com.jannetai.backend.security.UserPrincipal;
import com.jannetai.backend.service.UserProfileService;
import com.jannetai.backend.service.settings.PersonalSettingsService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /me was added in Phase 4 to give Flutter (and that phase's own
 * manual verification) a real protected endpoint to test JWT auth + RBAC
 * against. PUT /me is added in Phase 5 (Citizen Module, SRS 15.1 "profile
 * management") - see UserProfileService's Javadoc for exactly what is and
 * isn't in scope for that update. Broader user-management endpoints (list
 * users, change role, deactivate - SRS Admin screen "Add User,
 * Deactivate, Reset Password, Change Role") still belong to Phase 14
 * (Admin Module); not added here.
 *
 * GET/PATCH /me/settings added in Phase 15 (Personal Settings, SRS
 * 15.15) - deliberately a sibling of /me rather than folded into
 * UpdateProfileRequest, since these are USER-scoped {@code settings}
 * table rows (language/accessibility/officer-availability, plus
 * notification channel preferences), not {@code users} table columns
 * like the Phase 5 profile fields are.
 */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@Tag(name = "Users", description = "Authenticated user's own profile and personal settings")
public class UserController {

    private final UserProfileService userProfileService;
    private final PersonalSettingsService personalSettingsService;

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public UserProfileResponse me(@AuthenticationPrincipal UserPrincipal principal) {
        return UserProfileResponse.from(principal.getUser());
    }

    @PutMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public UserProfileResponse updateMe(@AuthenticationPrincipal UserPrincipal principal,
                                         @Valid @RequestBody UpdateProfileRequest request) {
        return userProfileService.updateProfile(principal.getUser(), request);
    }

    @GetMapping("/me/settings")
    @PreAuthorize("isAuthenticated()")
    public PersonalSettingsResponse getMySettings(@AuthenticationPrincipal UserPrincipal principal) {
        return personalSettingsService.getSettings(principal.getUser());
    }

    @PatchMapping("/me/settings")
    @PreAuthorize("isAuthenticated()")
    public PersonalSettingsResponse updateMySettings(@AuthenticationPrincipal UserPrincipal principal,
                                                       @RequestBody PersonalSettingsUpdateRequest request) {
        return personalSettingsService.updateSettings(principal.getUser(), request.language(),
                request.highContrastEnabled(), request.officerAvailabilityStatus());
    }
}
