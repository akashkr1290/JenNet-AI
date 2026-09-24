package com.jannetai.backend.controller;

import com.jannetai.backend.dto.department.RoutingRuleCreateRequest;
import com.jannetai.backend.dto.department.RoutingRuleResponse;
import com.jannetai.backend.security.UserPrincipal;
import com.jannetai.backend.service.department.RoutingRuleService;
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

/**
 * Phase 11's deliberately minimal Admin-only routing-rule management
 * (SRS 17.4 "Admin — Routing Rule Form"; 20.4 "Department / Admin APIs")
 * started with create + list-active only - see RoutingRuleService's
 * Javadoc and PROJECT_INTEGRATION.md Section 6 for that scope decision.
 * SUPER_ADMIN/ADMIN only, matching Table 10's own "Access: Admin only"
 * note - this is not a Department Head action (that role's routing-
 * relevant power in this phase is limited to complaint-level
 * reassignment/budget-approval, not category-wide routing policy).
 *
 * Phase 14 (Admin & Settings Module) adds {@link #history} and
 * {@link #deactivate} - see RoutingRuleService's class Javadoc.
 */
@RestController
@RequestMapping("/api/v1/admin/routing-rules")
@RequiredArgsConstructor
@Tag(name = "Routing Rules", description = "Admin routing-rule configuration (Phase 11, SRS 15.7/17.4)")
public class AdminRoutingRuleController {

    private final RoutingRuleService routingRuleService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public RoutingRuleResponse create(@AuthenticationPrincipal UserPrincipal principal,
                                       @Valid @RequestBody RoutingRuleCreateRequest request) {
        return routingRuleService.create(principal.getUser(), request);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN', 'DEPARTMENT_HEAD')")
    public List<RoutingRuleResponse> listActive() {
        return routingRuleService.listActive();
    }

    /** Phase 14: full history (active + inactive), for the Admin "View Change History" action. ADMIN/SUPER_ADMIN only, unlike {@link #listActive}. */
    @GetMapping("/history")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public List<RoutingRuleResponse> history() {
        return routingRuleService.listHistory();
    }

    /** Phase 14: explicit retirement of a single rule row (see RoutingRuleService's class Javadoc). */
    @PatchMapping("/{id}/deactivate")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public RoutingRuleResponse deactivate(@AuthenticationPrincipal UserPrincipal principal, @PathVariable Long id) {
        return routingRuleService.deactivate(principal.getUser(), id);
    }
}
