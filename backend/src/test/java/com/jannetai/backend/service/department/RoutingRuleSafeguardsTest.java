package com.jannetai.backend.service.department;

import com.jannetai.backend.entity.Department;
import com.jannetai.backend.entity.RoutingRule;
import com.jannetai.backend.entity.User;
import com.jannetai.backend.entity.enums.ComplaintCategory;
import com.jannetai.backend.entity.enums.Role;
import com.jannetai.backend.entity.enums.UserStatus;
import com.jannetai.backend.exception.InvalidStateTransitionException;
import com.jannetai.backend.repository.DepartmentRepository;
import com.jannetai.backend.repository.RoutingRuleRepository;
import com.jannetai.backend.repository.UserRepository;
import com.jannetai.backend.service.AuditService;
import com.jannetai.backend.service.admin.PlatformSettingsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Audit GAP-036: routing rules exist for every category, and cannot be removed leaving one unmapped. */
@ExtendWith(MockitoExtension.class)
class RoutingRuleSafeguardsTest {

    @Mock private RoutingRuleRepository routingRuleRepository;
    @Mock private DepartmentRepository departmentRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;
    @Mock private PlatformSettingsService platformSettingsService;

    private final User admin = User.builder().userId(1L).role(Role.SUPER_ADMIN).status(UserStatus.ACTIVE).build();

    private RoutingRule rule(long id, ComplaintCategory category) {
        return RoutingRule.builder().routingRuleId(id).issueCategory(category).isActive(true)
                .effectiveFrom(LocalDate.now().minusDays(1)).build();
    }

    // ---- deactivation guard ----

    @Test
    void deactivatingTheOnlyRuleForACategoryIsBlocked() {
        RoutingRuleService service = new RoutingRuleService(routingRuleRepository, departmentRepository, auditService,
                platformSettingsService);
        RoutingRule only = rule(5L, ComplaintCategory.POTHOLE);
        when(routingRuleRepository.findById(5L)).thenReturn(Optional.of(only));
        when(routingRuleRepository.findActiveByCategory(eq(ComplaintCategory.POTHOLE), any())).thenReturn(List.of(only));

        assertThatThrownBy(() -> service.deactivate(admin, 5L))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("POTHOLE");
        assertThat(only.getIsActive()).isTrue();
        verify(routingRuleRepository, never()).save(any());
    }

    @Test
    void deactivatingARuleIsAllowedWhenAnotherRuleCoversTheCategory() {
        RoutingRuleService service = new RoutingRuleService(routingRuleRepository, departmentRepository, auditService,
                platformSettingsService);
        RoutingRule old = rule(5L, ComplaintCategory.POTHOLE);
        RoutingRule replacement = rule(6L, ComplaintCategory.POTHOLE);
        old.setDepartment(Department.builder().departmentId(1L).name("Public Works").build());
        old.setCreatedBy(admin);
        when(routingRuleRepository.findById(5L)).thenReturn(Optional.of(old));
        when(routingRuleRepository.findActiveByCategory(eq(ComplaintCategory.POTHOLE), any()))
                .thenReturn(List.of(replacement, old));
        when(routingRuleRepository.save(any(RoutingRule.class))).thenAnswer(inv -> inv.getArgument(0));

        service.deactivate(admin, 5L);

        assertThat(old.getIsActive()).isFalse();
    }

    // ---- bootstrap seeding ----

    private RoutingRuleBootstrap bootstrap(String spec) {
        RoutingRuleBootstrap b = new RoutingRuleBootstrap(routingRuleRepository, departmentRepository, userRepository,
                auditService);
        ReflectionTestUtils.setField(b, "rulesSpec", spec);
        return b;
    }

    @Test
    void specIsParsedAndMalformedEntriesAreSkipped() {
        Map<ComplaintCategory, RoutingRuleBootstrap.Mapping> m = RoutingRuleBootstrap.parse(
                "POTHOLE=Public Works:72, NOT_A_CATEGORY=X:1, WATER_LEAKAGE=Water Supply:9999, GENERAL=General Triage:168");
        assertThat(m).containsOnlyKeys(ComplaintCategory.POTHOLE, ComplaintCategory.GENERAL);
        assertThat(m.get(ComplaintCategory.POTHOLE).departmentName()).isEqualTo("Public Works");
        assertThat(m.get(ComplaintCategory.POTHOLE).slaHours()).isEqualTo(72);
    }

    @Test
    void categoriesWithoutAnyRuleAreSeededOwnedByTheSuperAdmin() {
        Department publicWorks = Department.builder().departmentId(1L).name("Public Works").isActive(true).build();
        when(routingRuleRepository.existsByIssueCategory(ComplaintCategory.POTHOLE)).thenReturn(false);
        when(routingRuleRepository.existsByIssueCategory(ComplaintCategory.GENERAL)).thenReturn(true); // Admin configured it
        when(userRepository.findByRoleAndStatus(Role.SUPER_ADMIN, UserStatus.ACTIVE)).thenReturn(List.of(admin));
        when(departmentRepository.findByNameAndIsActiveTrue("Public Works")).thenReturn(Optional.of(publicWorks));
        when(routingRuleRepository.save(any(RoutingRule.class))).thenAnswer(inv -> inv.getArgument(0));

        bootstrap("POTHOLE=Public Works:72,GENERAL=General Triage:168").run();

        ArgumentCaptor<RoutingRule> saved = ArgumentCaptor.forClass(RoutingRule.class);
        verify(routingRuleRepository, times(1)).save(saved.capture());
        assertThat(saved.getValue().getIssueCategory()).isEqualTo(ComplaintCategory.POTHOLE);
        assertThat(saved.getValue().getDepartment()).isSameAs(publicWorks);
        assertThat(saved.getValue().getCreatedBy()).isSameAs(admin);
        assertThat(saved.getValue().getSlaHours()).isEqualTo(72);
        assertThat(saved.getValue().getIsActive()).isTrue();
    }

    @Test
    void nothingIsSeededWithoutASuperAdminAndNothingFails() {
        when(routingRuleRepository.existsByIssueCategory(ComplaintCategory.POTHOLE)).thenReturn(false);
        when(userRepository.findByRoleAndStatus(Role.SUPER_ADMIN, UserStatus.ACTIVE)).thenReturn(List.of());

        bootstrap("POTHOLE=Public Works:72").run();

        verify(routingRuleRepository, never()).save(any());
    }

    @Test
    void secondStartCreatesNothing() {
        lenient().when(routingRuleRepository.existsByIssueCategory(any())).thenReturn(true);

        bootstrap("POTHOLE=Public Works:72,GENERAL=General Triage:168").run();

        verify(routingRuleRepository, never()).save(any());
        verify(userRepository, never()).findByRoleAndStatus(any(), any());
    }
}
