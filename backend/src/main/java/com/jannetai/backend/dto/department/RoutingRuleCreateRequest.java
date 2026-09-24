package com.jannetai.backend.dto.department;

import com.jannetai.backend.entity.enums.ComplaintCategory;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * POST /api/v1/admin/routing-rules body (SRS 17.4 "Admin — Routing Rule
 * Form"; 20.4 "Department / Admin APIs": "Body: issue_category,
 * department_id, thresholds"). Field constraints below are taken directly
 * from Table 10 (17.4)'s own Validation column, not invented:
 * ai_confidence_threshold and duplicate_similarity_threshold both
 * range 50.00-99.00; sla_hours ranges 1-720; effective_from cannot be
 * before the current date.
 *
 * This endpoint started as a deliberately minimal Phase 11 addition
 * (create + list only) ahead of the full Admin Module - see
 * PROJECT_INTEGRATION.md Section 6 for that scope decision. Superseding a
 * rule for a category still normally means creating a new row with a
 * later effective_from (V14's own "keep a history of rule changes over
 * time" design), which RoutingRuleRepository#findCurrentActiveRule
 * resolves correctly without needing an explicit deactivation step.
 * Phase 14 additionally adds an explicit deactivate action and a full-
 * history read endpoint on AdminRoutingRuleController - see
 * RoutingRuleService's class Javadoc.
 */
public record RoutingRuleCreateRequest(
        @NotNull
        ComplaintCategory issueCategory,

        @NotNull
        Long departmentId,

        // Nullable on the wire despite Table 10 listing both as "Mandatory:
        // Yes" - the same table gives each a concrete Default Value
        // (85.00 / 80.00), which RoutingRuleService applies when omitted,
        // consistent with how every other default in this table
        // (effective_from -> current date) is handled.
        @DecimalMin("50.00")
        @DecimalMax("99.00")
        BigDecimal aiConfidenceThreshold,

        @DecimalMin("50.00")
        @DecimalMax("99.00")
        BigDecimal duplicateSimilarityThreshold,

        @NotNull
        @Min(1)
        @Max(720)
        Integer slaHours,

        // Nullable on the wire - Default Value "current date" (Table 10);
        // RoutingRuleService substitutes LocalDate.now() when omitted.
        // @FutureOrPresent still applies when a caller does supply one,
        // matching Table 10's Validation column ("Cannot be before current
        // date").
        @FutureOrPresent
        LocalDate effectiveFrom
) {
}
