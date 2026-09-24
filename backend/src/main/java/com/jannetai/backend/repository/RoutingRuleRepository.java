package com.jannetai.backend.repository;

import com.jannetai.backend.entity.RoutingRule;
import com.jannetai.backend.entity.enums.ComplaintCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Phase 11 (Department Assignment Module, SRS 15.7) adds the lookup this
 * module needs: the single "active" routing rule for a category, per the
 * resolution convention V14's own header comment documents ("the active
 * rule per category is the latest is_active row with
 * effective_from &lt;= CURRENT_DATE"). Also lists all active rules, for the
 * Admin routing-rule read endpoint. Phase 14 adds a full-history variant
 * (active + inactive) for the Admin "View Change History" action.
 */
@Repository
public interface RoutingRuleRepository extends JpaRepository<RoutingRule, Long> {

    @Query("""
            SELECT r FROM RoutingRule r
            WHERE r.issueCategory = :category
              AND r.isActive = true
              AND r.effectiveFrom <= :asOf
            ORDER BY r.effectiveFrom DESC, r.routingRuleId DESC
            """)
    List<RoutingRule> findActiveByCategory(@Param("category") ComplaintCategory category,
                                            @Param("asOf") LocalDate asOf);

    default Optional<RoutingRule> findCurrentActiveRule(ComplaintCategory category) {
        List<RoutingRule> matches = findActiveByCategory(category, LocalDate.now());
        return matches.isEmpty() ? Optional.empty() : Optional.of(matches.get(0));
    }

    List<RoutingRule> findByIsActiveTrueOrderByIssueCategoryAsc();

    /** Phase 14: full history (active + inactive) for the Admin "View Change History" action (SRS 16.3). */
    List<RoutingRule> findAllByOrderByIssueCategoryAscEffectiveFromDesc();
}
