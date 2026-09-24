package com.jannetai.backend.entity;

import com.jannetai.backend.entity.enums.ComplaintCategory;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Category -> department routing rules with AI/duplicate thresholds and SLA
 * hours. Has no seeded rows yet - both created_by requires a real user
 * (Phase 4 bootstrap); the "General Triage" department (V15) covers the
 * interim unmapped-category case. Maps onto
 * database/migrations/V14__create_routing_rules.sql.
 */
@Entity
@Table(name = "routing_rules")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RoutingRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "routing_rule_id")
    private Long routingRuleId;

    @Enumerated(EnumType.STRING)
    @Column(name = "issue_category", nullable = false, length = 50)
    private ComplaintCategory issueCategory;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "department_id", nullable = false)
    private Department department;

    @Column(name = "ai_confidence_threshold", nullable = false, precision = 4, scale = 2)
    private BigDecimal aiConfidenceThreshold;

    @Column(name = "duplicate_similarity_threshold", nullable = false, precision = 4, scale = 2)
    private BigDecimal duplicateSimilarityThreshold;

    @Column(name = "sla_hours", nullable = false)
    private Integer slaHours;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
