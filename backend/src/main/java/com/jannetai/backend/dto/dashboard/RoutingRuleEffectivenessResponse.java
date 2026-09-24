package com.jannetai.backend.dto.dashboard;

import com.jannetai.backend.entity.enums.ComplaintCategory;

/**
 * Phase 16 (Analytics Module, SRS 24.3 Admin Dashboard Charts:
 * "routing-rule effectiveness"). The SRS never defines what
 * "effectiveness" means numerically for a routing rule - a genuine SRS
 * gap, same category as Phase 13's undefined SLA-compliance-% formula
 * (see that phase's Javadoc/PROJECT_INTEGRATION.md Section 6). This
 * project resolves it as: for the rule's (category, department) pairing,
 * the same SLA-compliance-% formula already established in Phase 13
 * ({@code (total - escalated) / total * 100}) computed only over
 * complaints matching that exact category+department combination - a
 * rule whose routed complaints rarely breach SLA is "effective" by this
 * measure. Documented rather than silently picked; see
 * PROJECT_INTEGRATION.md Section 6 for the full decision record.
 */
public record RoutingRuleEffectivenessResponse(
        Long routingRuleId,
        ComplaintCategory category,
        String departmentName,
        Long complaintCount,
        Double slaCompliancePercent
) {
}
