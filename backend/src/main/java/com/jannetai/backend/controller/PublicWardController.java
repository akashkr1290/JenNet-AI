package com.jannetai.backend.controller;

import com.jannetai.backend.dto.user.WardResponse;
import com.jannetai.backend.service.WardService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Gap-backlog Patch 8 (Sep 2026 audit): a public, unauthenticated ward
 * lookup so the citizen registration screen can offer a ward picker
 * before a JWT exists.
 *
 * {@link WardController}'s {@code /api/v1/wards} stays authenticated -
 * that decision was correct against SRS 18's literal text ("all endpoints
 * except registration, login, and OTP verification require a valid JWT")
 * at the time it was made, but the SRS never anticipated a citizen needing
 * reference data (ward) as an *input to* registration itself, before any
 * token exists. Rather than silently loosening the existing authenticated
 * endpoint (which /users/me profile-update and other authenticated flows
 * already depend on staying auth-gated), this adds a second, narrower,
 * additive endpoint that is public by construction: it returns exactly
 * {@link WardResponse} - already documented as "public-safe" (id, name,
 * code only, no boundaryGeojson) - via the same read-only
 * {@link WardService}, so there is no new data-exposure surface, only a
 * new access path to data that was already safe to expose.
 *
 * See SecurityConfig's {@code /api/v1/public/wards/**} permitAll rule for
 * the other half of this decision.
 */
@RestController
@RequestMapping("/api/v1/public/wards")
@RequiredArgsConstructor
@Tag(name = "Wards", description = "Public read-only ward reference data (registration flow, pre-JWT)")
public class PublicWardController {

    private final WardService wardService;

    @GetMapping
    public List<WardResponse> listActiveWards() {
        return wardService.listActiveWards();
    }
}
