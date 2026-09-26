package com.jannetai.backend.controller;

import com.jannetai.backend.dto.admin.AdminCreateUserRequest;
import com.jannetai.backend.dto.admin.AdminCreateUserResponse;
import com.jannetai.backend.dto.admin.AdminUpdateRoleRequest;
import com.jannetai.backend.dto.admin.AdminUpdateStatusRequest;
import com.jannetai.backend.dto.auth.SimpleMessageResponse;
import com.jannetai.backend.dto.auth.UserProfileResponse;
import com.jannetai.backend.entity.enums.Role;
import com.jannetai.backend.entity.enums.UserStatus;
import com.jannetai.backend.security.UserPrincipal;
import com.jannetai.backend.service.admin.AdminUserService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 14 (Admin & Settings Module, SRS 15.11 "Admin Module"; 16.3
 * "User & Role Management" screen; Security 27.5 "sessions can be
 * remotely revoked by an Admin/Super Admin"). All endpoints are
 * ADMIN/SUPER_ADMIN only at the gate; the finer SUPER_ADMIN-only-for-
 * ADMIN-accounts rule lives in {@link AdminUserService} (see its class
 * Javadoc's "ROLE-PRIVILEGE MATRIX"), not duplicated here.
 */
@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
@Tag(name = "Admin Users", description = "Staff account provisioning and role/status management (Phase 14, SRS 15.11/16.3)")
public class AdminUserController {

    private final AdminUserService adminUserService;
    private final com.jannetai.backend.service.privacy.PersonalDataService personalDataService; // audit GAP-041

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public Page<UserProfileResponse> list(@RequestParam(required = false) Role role,
                                           @RequestParam(required = false) UserStatus status,
                                           @RequestParam(required = false) Long departmentId,
                                           @RequestParam(required = false) String search,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "20") int pageSize) {
        return adminUserService.list(role, status, departmentId, search, page, pageSize);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public AdminCreateUserResponse create(@AuthenticationPrincipal UserPrincipal principal,
                                           @Valid @RequestBody AdminCreateUserRequest request) {
        return adminUserService.createStaff(principal.getUser(), request);
    }

    @PatchMapping("/{id}/role")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public UserProfileResponse updateRole(@AuthenticationPrincipal UserPrincipal principal,
                                           @PathVariable Long id,
                                           @Valid @RequestBody AdminUpdateRoleRequest request) {
        return adminUserService.updateRole(principal.getUser(), id, request.role());
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public UserProfileResponse updateStatus(@AuthenticationPrincipal UserPrincipal principal,
                                             @PathVariable Long id,
                                             @Valid @RequestBody AdminUpdateStatusRequest request) {
        return adminUserService.updateStatus(principal.getUser(), id, request.status());
    }

    @PostMapping("/{id}/reset-password")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public SimpleMessageResponse resetPassword(@AuthenticationPrincipal UserPrincipal principal,
                                                @PathVariable Long id) {
        adminUserService.triggerPasswordReset(principal.getUser(), id);
        return new SimpleMessageResponse("Password reset OTP sent to the user's registered mobile number");
    }

    @PostMapping("/{id}/revoke-sessions")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public SimpleMessageResponse revokeSessions(@AuthenticationPrincipal UserPrincipal principal,
                                                 @PathVariable Long id) {
        adminUserService.revokeSessions(principal.getUser(), id);
        return new SimpleMessageResponse("All active sessions revoked for this user");
    }

    /**
     * Audit GAP-041 (SRS 24): SUPER_ADMIN erases a person's data on a request
     * received outside the app (reference = the request's ticket/letter id).
     */
    @PostMapping("/{id}/erase")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void erase(@AuthenticationPrincipal UserPrincipal principal, @PathVariable Long id,
                      @jakarta.validation.Valid @RequestBody com.jannetai.backend.dto.privacy.ErasureRequest.OnBehalf request) {
        personalDataService.eraseOnBehalf(principal.getUser(), id, request.reference());
    }
}
