package com.jannetai.backend.service.department;

import com.jannetai.backend.dto.department.RoutingRuleCreateRequest;
import com.jannetai.backend.dto.department.RoutingRuleResponse;
import com.jannetai.backend.entity.Department;
import com.jannetai.backend.entity.RoutingRule;
import com.jannetai.backend.entity.User;
import com.jannetai.backend.exception.InvalidStateTransitionException;
import com.jannetai.backend.exception.ResourceNotFoundException;
import com.jannetai.backend.repository.DepartmentRepository;
import com.jannetai.backend.repository.RoutingRuleRepository;
import com.jannetai.backend.service.AuditService;
import com.jannetai.backend.service.admin.PlatformSettingKey;
import com.jannetai.backend.service.admin.PlatformSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Phase 11's deliberately minimal Admin routing-rule management (create +
 * list only) - see PROJECT_INTEGRATION.md Section 6 for the scope
 * decision that put this here, ahead of the full Admin Module (Phase 14).
 * Superseding a category's routing means creating a new row with a later
 * {@code effectiveFrom} ({@link RoutingRuleRepository#findCurrentActiveRule}'s
 * "latest active row" resolution already handles this correctly without
 * needing an explicit deactivation step - V14's own "keep a history of
 * rule changes over time" design) - this remains the primary way to
 * change a category's routing.
 *
 * PHASE 14 ADDITIONS (Admin & Settings Module):
 * <ul>
 *   <li>{@link #listHistory()} - full history (active + inactive), for
 *   the Admin "View Change History" action (SRS 16.3 Routing screen).</li>
 *   <li>{@link #deactivate} - lets an Admin explicitly retire a specific
 *   row. Since {@link RoutingRuleRepository#findCurrentActiveRule} always
 *   resolves to the latest still-active row, deactivating the current
 *   one causes the next-latest active row for that category (if any) to
 *   become current - the closest available analog to the screen's
 *   "Revert to Default"/rollback action, given this schema's supersede-
 *   by-new-row design has no separate "default row" concept to revert
 *   to. Documented here rather than silently reinterpreted.</li>
 *   <li>{@link #create}'s AI-confidence/duplicate-similarity defaults now
 *   check {@link PlatformSettingsService} for an Admin-set PLATFORM
 *   override (SRS 15.15) before falling back to the literal Table 10
 *   defaults below - same override-with-unchanged-fallback pattern used
 *   by EscalationSchedulerService.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class RoutingRuleService {

    /** Table 10 (17.4) Default Value column: 85.00 / 80.00. Fallback when no PLATFORM setting override exists. */
    private static final BigDecimal DEFAULT_AI_CONFIDENCE_THRESHOLD = new BigDecimal("85.00");
    private static final BigDecimal DEFAULT_DUPLICATE_SIMILARITY_THRESHOLD = new BigDecimal("80.00");

    private final RoutingRuleRepository routingRuleRepository;
    private final DepartmentRepository departmentRepository;
    private final AuditService auditService;
    private final PlatformSettingsService platformSettingsService;

    @Transactional
    public RoutingRuleResponse create(User admin, RoutingRuleCreateRequest request) {
        Department department = departmentRepository.findById(request.departmentId())
                .orElseThrow(() -> new ResourceNotFoundException("Department not found: " + request.departmentId()));

        RoutingRule rule = RoutingRule.builder()
                .issueCategory(request.issueCategory())
                .department(department)
                .aiConfidenceThreshold(request.aiConfidenceThreshold() != null
                        ? request.aiConfidenceThreshold() : defaultAiConfidenceThreshold())
                .duplicateSimilarityThreshold(request.duplicateSimilarityThreshold() != null
                        ? request.duplicateSimilarityThreshold() : defaultDuplicateSimilarityThreshold())
                .slaHours(request.slaHours())
                .effectiveFrom(request.effectiveFrom() != null ? request.effectiveFrom() : LocalDate.now())
                .isActive(true)
                .createdBy(admin)
                .build();
        rule = routingRuleRepository.save(rule);

        auditService.record(admin, "ROUTING_RULE_CREATED", "ROUTING_RULE", rule.getRoutingRuleId(),
                "{\"issue_category\":\"" + rule.getIssueCategory() + "\",\"department_id\":"
                        + department.getDepartmentId() + "}");

        return RoutingRuleResponse.from(rule);
    }

    @Transactional(readOnly = true)
    public List<RoutingRuleResponse> listActive() {
        return routingRuleRepository.findByIsActiveTrueOrderByIssueCategoryAsc()
                .stream().map(RoutingRuleResponse::from).toList();
    }

    /** Phase 14: full history (active + inactive), newest-first per category, for the Admin "View Change History" action. */
    @Transactional(readOnly = true)
    public List<RoutingRuleResponse> listHistory() {
        return routingRuleRepository.findAllByOrderByIssueCategoryAscEffectiveFromDesc()
                .stream().map(RoutingRuleResponse::from).toList();
    }

    /** Phase 14: explicit retirement of a single rule row - see class Javadoc for why this is the closest available "Revert to Default" analog. */
    @Transactional
    public RoutingRuleResponse deactivate(User admin, Long routingRuleId) {
        RoutingRule rule = routingRuleRepository.findById(routingRuleId)
                .orElseThrow(() -> new ResourceNotFoundException("Routing rule not found: " + routingRuleId));
        // Audit GAP-036 (SRS 15.11 exception: block changes that would leave a
        // category unmapped). Another rule for the same category must be in
        // force today; otherwise every complaint of that category would silently
        // fall back to the triage department.
        boolean anotherRuleInForce = routingRuleRepository
                .findActiveByCategory(rule.getIssueCategory(), LocalDate.now()).stream()
                .anyMatch(other -> !other.getRoutingRuleId().equals(rule.getRoutingRuleId()));
        if (Boolean.TRUE.equals(rule.getIsActive()) && !anotherRuleInForce) {
            throw new InvalidStateTransitionException("Deactivating this rule would leave category "
                    + rule.getIssueCategory() + " without a routing rule. Create the replacement rule first.");
        }
        rule.setIsActive(false);
        RoutingRule saved = routingRuleRepository.save(rule);

        auditService.record(admin, "ROUTING_RULE_DEACTIVATED", "ROUTING_RULE", saved.getRoutingRuleId(),
                "{\"issue_category\":\"" + saved.getIssueCategory() + "\"}");

        return RoutingRuleResponse.from(saved);
    }

    private BigDecimal defaultAiConfidenceThreshold() {
        return platformSettingsService.getOverride(PlatformSettingKey.AI_CONFIDENCE_THRESHOLD)
                .map(BigDecimal::new).orElse(DEFAULT_AI_CONFIDENCE_THRESHOLD);
    }

    private BigDecimal defaultDuplicateSimilarityThreshold() {
        return platformSettingsService.getOverride(PlatformSettingKey.DUPLICATE_SIMILARITY_THRESHOLD)
                .map(BigDecimal::new).orElse(DEFAULT_DUPLICATE_SIMILARITY_THRESHOLD);
    }
}
