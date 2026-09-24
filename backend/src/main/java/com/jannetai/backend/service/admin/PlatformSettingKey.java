package com.jannetai.backend.service.admin;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Optional;

/**
 * Phase 14 (Admin & Settings Module, SRS 15.15 "Admin-level default
 * thresholds (AI confidence, duplicate similarity, SLA timers)"). This is
 * a closed, explicit registry - {@link PlatformSettingsService} only ever
 * reads/writes keys listed here, so an Admin can never accidentally create
 * an arbitrary, unvalidated row in the {@code settings} table (V13's own
 * design leaves that guard to the application layer).
 *
 * Each key's "recommended default" mirrors a concrete number already
 * pinned elsewhere in this codebase (see the Javadoc reference on each
 * constant) - this registry does not invent new numbers, it exposes the
 * existing ones for Admin override. The registry's default is shown to
 * the Admin as a reference value only; the value actually used when no
 * override row exists remains whatever that original {@code @Value}-bound
 * field resolves to at runtime (see each consumer's own fallback logic),
 * so this class deliberately does not claim to be the source of truth for
 * "what happens when unset" - only for "what's a valid override".
 *
 * SCOPE NOTE (SRS 15.15 Exceptions: "attempts to set conflicting
 * thresholds, e.g. duplicate-merge threshold higher than duplicate-review
 * threshold, are blocked"): this codebase has a single
 * duplicate_similarity_threshold field (RoutingRule, V14), not separate
 * merge/review thresholds - no second field exists anywhere in the schema
 * to conflict-check against. Rather than invent a threshold the rest of
 * the system doesn't use, that specific Exception rule is treated as
 * inapplicable here and is not implemented - documented in
 * PROJECT_INTEGRATION.md Section 6 rather than silently ignored.
 */
public enum PlatformSettingKey {

    /** RoutingRuleService.DEFAULT_AI_CONFIDENCE_THRESHOLD / Table 10 (17.4). */
    AI_CONFIDENCE_THRESHOLD("ai_confidence_threshold", Type.DECIMAL, "85.00", "50.00", "99.00"),

    /** RoutingRuleService.DEFAULT_DUPLICATE_SIMILARITY_THRESHOLD / Table 10 (17.4). */
    DUPLICATE_SIMILARITY_THRESHOLD("duplicate_similarity_threshold", Type.DECIMAL, "80.00", "50.00", "99.00"),

    /** EscalationSchedulerService's app.escalation.sla-hours.critical / SRS 14.3. */
    SLA_HOURS_CRITICAL("sla_hours_critical", Type.INTEGER, "24", "1", "720"),

    /** EscalationSchedulerService's app.escalation.sla-hours.high / SRS 14.3. */
    SLA_HOURS_HIGH("sla_hours_high", Type.INTEGER, "72", "1", "720"),

    /** EscalationSchedulerService's app.escalation.sla-hours.medium / SRS 14.3 ("7 days"). */
    SLA_HOURS_MEDIUM("sla_hours_medium", Type.INTEGER, "168", "1", "720"),

    /** EscalationSchedulerService's app.escalation.sla-hours.low / SRS 14.3 ("14 days"). */
    SLA_HOURS_LOW("sla_hours_low", Type.INTEGER, "336", "1", "720"),

    /** ComplaintService.budgetApprovalThresholdInr / SRS 15.9. */
    BUDGET_APPROVAL_THRESHOLD_INR("budget_approval_threshold_inr", Type.INTEGER, "50000", "0", "10000000");

    public enum Type { DECIMAL, INTEGER }

    private final String key;
    private final Type type;
    private final String recommendedDefault;
    private final String min;
    private final String max;

    PlatformSettingKey(String key, Type type, String recommendedDefault, String min, String max) {
        this.key = key;
        this.type = type;
        this.recommendedDefault = recommendedDefault;
        this.min = min;
        this.max = max;
    }

    public String key() {
        return key;
    }

    public Type type() {
        return type;
    }

    public String recommendedDefault() {
        return recommendedDefault;
    }

    public String min() {
        return min;
    }

    public String max() {
        return max;
    }

    public static Optional<PlatformSettingKey> fromKey(String key) {
        return Arrays.stream(values()).filter(k -> k.key.equals(key)).findFirst();
    }

    /** @throws IllegalArgumentException if rawValue isn't a valid number of this key's type, or is out of range. */
    public void validate(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            throw new IllegalArgumentException(key + " must not be blank");
        }
        try {
            if (type == Type.DECIMAL) {
                BigDecimal value = new BigDecimal(rawValue.trim());
                if (value.compareTo(new BigDecimal(min)) < 0 || value.compareTo(new BigDecimal(max)) > 0) {
                    throw new IllegalArgumentException(key + " must be between " + min + " and " + max);
                }
            } else {
                long value = Long.parseLong(rawValue.trim());
                if (value < Long.parseLong(min) || value > Long.parseLong(max)) {
                    throw new IllegalArgumentException(key + " must be between " + min + " and " + max);
                }
            }
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(key + " must be a valid number");
        }
    }
}
