package com.jannetai.backend.service.department;

import com.jannetai.backend.entity.Department;
import com.jannetai.backend.entity.RoutingRule;
import com.jannetai.backend.entity.User;
import com.jannetai.backend.entity.enums.ComplaintCategory;
import com.jannetai.backend.entity.enums.Role;
import com.jannetai.backend.entity.enums.UserStatus;
import com.jannetai.backend.repository.DepartmentRepository;
import com.jannetai.backend.repository.RoutingRuleRepository;
import com.jannetai.backend.repository.UserRepository;
import com.jannetai.backend.service.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Audit GAP-036 (SRS 15.7 validation: "a routing rule must exist for every
 * supported issue category before go-live"). Fresh installs had zero routing
 * rules, so every complaint went to the fallback department.
 *
 * <p>Seeding cannot be a Flyway migration: routing_rules.created_by is a NOT
 * NULL foreign key to users, and no user exists when migrations run. This
 * runner starts after {@code SuperAdminBootstrap} and, for every category with
 * NO routing rule at all (active or historical), creates one from
 * {@code app.routing-bootstrap.rules}, owned by the first active Super Admin.
 * Categories an Admin has ever configured are left alone, so this never
 * overrides an Admin decision. Idempotent: a second start creates nothing.
 */
@Component
@Order(10)
public class RoutingRuleBootstrap implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(RoutingRuleBootstrap.class);
    private static final BigDecimal DEFAULT_AI_CONFIDENCE = new BigDecimal("85.00");
    private static final BigDecimal DEFAULT_DUPLICATE_SIMILARITY = new BigDecimal("80.00");

    private final RoutingRuleRepository routingRuleRepository;
    private final DepartmentRepository departmentRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;

    @Value("${app.routing-bootstrap.enabled:true}")
    private boolean enabled = true;

    @Value("${app.routing-bootstrap.rules:}")
    private String rulesSpec = "";

    public RoutingRuleBootstrap(RoutingRuleRepository routingRuleRepository, DepartmentRepository departmentRepository,
                                UserRepository userRepository, AuditService auditService) {
        this.routingRuleRepository = routingRuleRepository;
        this.departmentRepository = departmentRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
    }

    /** One parsed "CATEGORY=Department:hours" entry. */
    record Mapping(ComplaintCategory category, String departmentName, int slaHours) {
    }

    /** Parses the spec; malformed entries are reported and skipped, never fatal. */
    static Map<ComplaintCategory, Mapping> parse(String spec) {
        Map<ComplaintCategory, Mapping> mappings = new LinkedHashMap<>();
        if (spec == null || spec.isBlank()) {
            return mappings;
        }
        for (String entry : spec.split(",")) {
            String trimmed = entry.trim();
            int eq = trimmed.indexOf('=');
            int colon = trimmed.lastIndexOf(':');
            if (eq <= 0 || colon <= eq + 1) {
                log.warn("Ignoring malformed routing bootstrap entry '{}' (expected CATEGORY=Department:hours)", trimmed);
                continue;
            }
            try {
                ComplaintCategory category = ComplaintCategory.valueOf(trimmed.substring(0, eq).trim());
                String department = trimmed.substring(eq + 1, colon).trim();
                int hours = Integer.parseInt(trimmed.substring(colon + 1).trim());
                if (department.isEmpty() || hours < 1 || hours > 720) { // routing_rules CHECK: 1-720
                    throw new IllegalArgumentException("department empty or SLA hours outside 1-720");
                }
                mappings.put(category, new Mapping(category, department, hours));
            } catch (IllegalArgumentException e) {
                log.warn("Ignoring routing bootstrap entry '{}': {}", trimmed, e.getMessage());
            }
        }
        return mappings;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (!enabled) {
            return;
        }
        Map<ComplaintCategory, Mapping> mappings = parse(rulesSpec);
        List<ComplaintCategory> missing = mappings.keySet().stream()
                .filter(category -> !routingRuleRepository.existsByIssueCategory(category))
                .toList();
        if (missing.isEmpty()) {
            return;
        }
        Optional<User> owner = userRepository.findByRoleAndStatus(Role.SUPER_ADMIN, UserStatus.ACTIVE).stream().findFirst();
        if (owner.isEmpty()) {
            log.warn("No active SUPER_ADMIN yet - {} routing rule(s) not seeded ({}); set BOOTSTRAP_SUPER_ADMIN_* "
                    + "or create the rules in Admin > Routing Rules", missing.size(), missing);
            return;
        }
        for (ComplaintCategory category : missing) {
            Mapping mapping = mappings.get(category);
            Optional<Department> department = departmentRepository.findByNameAndIsActiveTrue(mapping.departmentName());
            if (department.isEmpty()) {
                log.warn("Routing bootstrap: department '{}' for {} not found or inactive - rule not seeded",
                        mapping.departmentName(), category);
                continue;
            }
            RoutingRule rule = routingRuleRepository.save(RoutingRule.builder()
                    .issueCategory(category)
                    .department(department.get())
                    .aiConfidenceThreshold(DEFAULT_AI_CONFIDENCE)
                    .duplicateSimilarityThreshold(DEFAULT_DUPLICATE_SIMILARITY)
                    .slaHours(mapping.slaHours())
                    .effectiveFrom(LocalDate.now())
                    .isActive(true)
                    .createdBy(owner.get())
                    .build());
            auditService.record(owner.get(), "ROUTING_RULE_SEEDED", "ROUTING_RULE", rule.getRoutingRuleId(),
                    "{\"issue_category\":\"" + category.name() + "\",\"department_id\":"
                            + department.get().getDepartmentId() + "}");
            log.info("Seeded routing rule {} -> {} (SLA {} h)", category, mapping.departmentName(), mapping.slaHours());
        }
    }
}
