package com.jannetai.backend.service.complaint;

import com.jannetai.backend.entity.enums.ComplaintCategory;
import com.jannetai.backend.repository.RoutingRuleRepository;
import com.jannetai.backend.service.admin.PlatformSettingKey;
import com.jannetai.backend.service.admin.PlatformSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Audit GAP-011 (SRS 15.11 / 15.15 / 17.4: AI confidence and duplicate
 * similarity thresholds are configurable by the Admin; US-09: changes take
 * effect without a restart). Resolved per request, in this order:
 * <ol>
 *   <li>the category's active routing rule (routing_rules.ai_confidence_threshold /
 *       duplicate_similarity_threshold);</li>
 *   <li>the platform setting (PATCH /admin/settings: ai_confidence_threshold /
 *       duplicate_similarity_threshold);</li>
 *   <li>ai-service's own environment default (sent as null).</li>
 * </ol>
 * Previously none of these reached ai-service at all.
 */
@Component
@RequiredArgsConstructor
public class AiThresholdResolver {

    private final RoutingRuleRepository routingRuleRepository;
    private final PlatformSettingsService platformSettingsService;

    /** Platform-level auto-approve threshold, or null to use ai-service's default. */
    public BigDecimal platformConfidenceThreshold() {
        return platformSettingsService.getOverride(PlatformSettingKey.AI_CONFIDENCE_THRESHOLD)
                .map(BigDecimal::new).orElse(null);
    }

    /** Active routing-rule thresholds keyed by category name (only categories that have a rule). */
    public Map<String, BigDecimal> categoryConfidenceThresholds() {
        Map<String, BigDecimal> thresholds = new LinkedHashMap<>();
        for (ComplaintCategory category : ComplaintCategory.values()) {
            routingRuleRepository.findCurrentActiveRule(category)
                    .map(rule -> rule.getAiConfidenceThreshold())
                    .ifPresent(value -> thresholds.put(category.name(), value));
        }
        return thresholds;
    }

    /** Auto-merge similarity threshold for a complaint of this category, or null for ai-service's default. */
    public BigDecimal duplicateThreshold(ComplaintCategory category) {
        if (category != null) {
            BigDecimal fromRule = routingRuleRepository.findCurrentActiveRule(category)
                    .map(rule -> rule.getDuplicateSimilarityThreshold())
                    .orElse(null);
            if (fromRule != null) {
                return fromRule;
            }
        }
        return platformSettingsService.getOverride(PlatformSettingKey.DUPLICATE_SIMILARITY_THRESHOLD)
                .map(BigDecimal::new).orElse(null);
    }
}
