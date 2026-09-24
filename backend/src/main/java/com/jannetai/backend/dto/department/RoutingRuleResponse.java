package com.jannetai.backend.dto.department;

import com.jannetai.backend.entity.RoutingRule;
import com.jannetai.backend.entity.enums.ComplaintCategory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record RoutingRuleResponse(
        Long routingRuleId,
        ComplaintCategory issueCategory,
        Long departmentId,
        String departmentName,
        BigDecimal aiConfidenceThreshold,
        BigDecimal duplicateSimilarityThreshold,
        Integer slaHours,
        LocalDate effectiveFrom,
        Boolean isActive,
        Long createdByUserId,
        LocalDateTime createdAt
) {
    public static RoutingRuleResponse from(RoutingRule r) {
        return new RoutingRuleResponse(
                r.getRoutingRuleId(),
                r.getIssueCategory(),
                r.getDepartment().getDepartmentId(),
                r.getDepartment().getName(),
                r.getAiConfidenceThreshold(),
                r.getDuplicateSimilarityThreshold(),
                r.getSlaHours(),
                r.getEffectiveFrom(),
                r.getIsActive(),
                r.getCreatedBy().getUserId(),
                r.getCreatedAt()
        );
    }
}
