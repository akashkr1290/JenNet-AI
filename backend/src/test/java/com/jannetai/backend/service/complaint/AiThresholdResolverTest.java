package com.jannetai.backend.service.complaint;

import com.jannetai.backend.entity.RoutingRule;
import com.jannetai.backend.entity.enums.ComplaintCategory;
import com.jannetai.backend.repository.RoutingRuleRepository;
import com.jannetai.backend.service.admin.PlatformSettingKey;
import com.jannetai.backend.service.admin.PlatformSettingsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/** Audit GAP-011: routing rule -> platform setting -> ai-service default. */
@ExtendWith(MockitoExtension.class)
class AiThresholdResolverTest {

    @Mock private RoutingRuleRepository routingRuleRepository;
    @Mock private PlatformSettingsService platformSettingsService;
    @InjectMocks private AiThresholdResolver resolver;

    @Test
    void categoryRuleThresholdsAreSentPerCategory() {
        lenient().when(routingRuleRepository.findCurrentActiveRule(any())).thenReturn(Optional.empty());
        when(routingRuleRepository.findCurrentActiveRule(ComplaintCategory.POTHOLE)).thenReturn(Optional.of(
                RoutingRule.builder().aiConfidenceThreshold(new BigDecimal("75.00")).build()));

        assertThat(resolver.categoryConfidenceThresholds()).containsExactly(
                java.util.Map.entry("POTHOLE", new BigDecimal("75.00")));
    }

    @Test
    void platformSettingIsTheFallbackAndAbsentMeansAiServiceDefault() {
        when(platformSettingsService.getOverride(PlatformSettingKey.AI_CONFIDENCE_THRESHOLD)).thenReturn(Optional.of("80.00"));
        assertThat(resolver.platformConfidenceThreshold()).isEqualByComparingTo("80.00");

        when(platformSettingsService.getOverride(PlatformSettingKey.AI_CONFIDENCE_THRESHOLD)).thenReturn(Optional.empty());
        assertThat(resolver.platformConfidenceThreshold()).isNull();
    }

    @Test
    void duplicateThresholdPrefersTheCategoryRule() {
        when(routingRuleRepository.findCurrentActiveRule(ComplaintCategory.GARBAGE_OVERFLOW)).thenReturn(Optional.of(
                RoutingRule.builder().duplicateSimilarityThreshold(new BigDecimal("70.00")).build()));
        assertThat(resolver.duplicateThreshold(ComplaintCategory.GARBAGE_OVERFLOW)).isEqualByComparingTo("70.00");

        when(routingRuleRepository.findCurrentActiveRule(ComplaintCategory.POTHOLE)).thenReturn(Optional.empty());
        when(platformSettingsService.getOverride(PlatformSettingKey.DUPLICATE_SIMILARITY_THRESHOLD)).thenReturn(Optional.of("85.00"));
        assertThat(resolver.duplicateThreshold(ComplaintCategory.POTHOLE)).isEqualByComparingTo("85.00");
    }
}
