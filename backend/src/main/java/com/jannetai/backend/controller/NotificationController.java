package com.jannetai.backend.controller;

import com.jannetai.backend.dto.notification.DeviceTokenRequest;
import com.jannetai.backend.dto.notification.NotificationPreferencesResponse;
import com.jannetai.backend.dto.notification.NotificationPreferencesUpdateRequest;
import com.jannetai.backend.dto.notification.NotificationResponse;
import com.jannetai.backend.security.UserPrincipal;
import com.jannetai.backend.service.notification.NotificationService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 15 (Notification Module, SRS 15.13 / 20.5). No role restriction
 * beyond authentication - every endpoint here scopes strictly to the
 * calling user's own notifications/preferences via
 * {@code principal.getUser()} (never a path/query id), so any
 * authenticated role (CITIZEN, GOVERNMENT_OFFICER, DEPARTMENT_HEAD,
 * ADMIN, SUPER_ADMIN) can call these for themselves; there is no
 * "view another user's notifications" capability to gate.
 */
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
@Tag(name = "Notifications", description = "Personal notification list and channel preferences (Phase 15, SRS 15.13)")
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping
    public Page<NotificationResponse> list(@AuthenticationPrincipal UserPrincipal principal,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "20") int pageSize) {
        return notificationService.listForUser(principal.getUser(), PageRequest.of(page, pageSize));
    }

    @GetMapping("/preferences")
    public NotificationPreferencesResponse getPreferences(@AuthenticationPrincipal UserPrincipal principal) {
        return notificationService.getPreferences(principal.getUser());
    }

    @PutMapping("/preferences")
    public NotificationPreferencesResponse updatePreferences(@AuthenticationPrincipal UserPrincipal principal,
                                                               @RequestBody NotificationPreferencesUpdateRequest request) {
        return notificationService.updatePreferences(principal.getUser(), request.smsEnabled(),
                request.pushEnabled(), request.emailEnabled());
    }

    /** Gap-backlog Patch 14/16 (Sep 2026 audit): register/refresh this device's FCM token for push delivery. */
    @PostMapping("/device-token")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void registerDeviceToken(@AuthenticationPrincipal UserPrincipal principal,
                                     @Valid @RequestBody DeviceTokenRequest request) {
        notificationService.registerDeviceToken(principal.getUser(), request.deviceToken(), request.platform());
    }

    /** Called on logout/uninstall so a stale token stops being sent to. */
    @DeleteMapping("/device-token/{deviceToken}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deregisterDeviceToken(@PathVariable String deviceToken) {
        notificationService.deregisterDeviceToken(deviceToken);
    }
}
