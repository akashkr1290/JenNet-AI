package com.jannetai.backend.service.admin;

import com.jannetai.backend.dto.department.DepartmentUpsertRequest;
import com.jannetai.backend.dto.ward.WardUpsertRequest;
import com.jannetai.backend.entity.Department;
import com.jannetai.backend.entity.User;
import com.jannetai.backend.entity.Ward;
import com.jannetai.backend.entity.enums.Role;
import com.jannetai.backend.entity.enums.UserStatus;
import com.jannetai.backend.repository.DepartmentRepository;
import com.jannetai.backend.repository.RoutingRuleRepository;
import com.jannetai.backend.repository.UserRepository;
import com.jannetai.backend.repository.WardRepository;
import com.jannetai.backend.service.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Audit GAP-020 (SRS 15.11): Admin ward and department configuration. NOT EXECUTED here via Maven. */
@ExtendWith(MockitoExtension.class)
class WardAndDepartmentAdminServiceTest {

    @Mock private WardRepository wardRepository;
    @Mock private DepartmentRepository departmentRepository;
    @Mock private UserRepository userRepository;
    @Mock private RoutingRuleRepository routingRuleRepository;
    @Mock private AuditService auditService;

    private WardAdminService wards;
    private DepartmentAdminService departments;
    private final User admin = User.builder().userId(1L).role(Role.ADMIN).fullName("Admin").build();

    static String square(double x0, double y0, double x1, double y1) {
        return "{\"type\":\"Polygon\",\"coordinates\":[[[" + x0 + "," + y0 + "],[" + x1 + "," + y0 + "],[" + x1 + "," + y1
                + "],[" + x0 + "," + y1 + "],[" + x0 + "," + y0 + "]]]}";
    }

    @BeforeEach
    void setUp() {
        wards = new WardAdminService(wardRepository, auditService);
        departments = new DepartmentAdminService(departmentRepository, userRepository, routingRuleRepository, auditService);
        ReflectionTestUtils.setField(departments, "fallbackDepartmentName", "General Triage");
        lenient().when(wardRepository.save(any(Ward.class))).thenAnswer(inv -> {
            Ward w = inv.getArgument(0);
            if (w.getWardId() == null) {
                w.setWardId(50L);
            }
            return w;
        });
        lenient().when(departmentRepository.save(any(Department.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void wardWithAValidNonOverlappingBoundaryIsSavedNormalisedAndAudited() {
        Ward neighbour = Ward.builder().wardId(2L).name("Ward 2").isActive(true).boundaryGeojson(square(1, 0, 2, 1)).build();
        when(wardRepository.findByIsActiveTrueOrderByNameAsc()).thenReturn(List.of(neighbour));

        var saved = wards.create(admin, new WardUpsertRequest(" Ward 1 ", "W1", square(0, 0, 1, 1)));

        assertThat(saved.name()).isEqualTo("Ward 1");
        assertThat(saved.hasBoundary()).isTrue();
        assertThat(saved.boundaryGeojson()).startsWith("{\"type\":\"Polygon\"");
        verify(auditService).record(eq(admin), eq("WARD_CREATED"), eq("WARD"), eq(50L), anyString());
    }

    @Test
    void overlappingAnotherActiveWardIsAConflictAndNothingIsSaved() {
        Ward other = Ward.builder().wardId(2L).name("Ward 2").isActive(true).boundaryGeojson(square(0.5, 0.5, 2, 2)).build();
        when(wardRepository.findByIsActiveTrueOrderByNameAsc()).thenReturn(List.of(other));

        assertThatThrownBy(() -> wards.create(admin, new WardUpsertRequest("Ward 1", null, square(0, 0, 1, 1))))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("Ward 2");
        verify(wardRepository, never()).save(any());
    }

    @Test
    void invalidGeometryIsA400AndDuplicateNameA409() {
        assertThatThrownBy(() -> wards.create(admin, new WardUpsertRequest("W", null, "{\"type\":\"Point\",\"coordinates\":[1,2]}")))
                .isInstanceOf(IllegalArgumentException.class);
        when(wardRepository.findFirstByName("Ward 9")).thenReturn(Optional.of(Ward.builder().wardId(9L).name("Ward 9").build()));
        assertThatThrownBy(() -> wards.create(admin, new WardUpsertRequest("Ward 9", null, null)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("already exists");
    }

    @Test
    void updateKeepsThePreviousBoundaryInTheAuditTrail() {
        Ward ward = Ward.builder().wardId(3L).name("Ward 3").isActive(true).boundaryGeojson(square(0, 0, 1, 1)).build();
        when(wardRepository.findById(3L)).thenReturn(Optional.of(ward));
        when(wardRepository.findByIsActiveTrueOrderByNameAsc()).thenReturn(List.of(ward)); // itself is skipped

        wards.update(admin, 3L, new WardUpsertRequest("Ward 3", null, square(0, 0, 2, 2)));

        ArgumentCaptor<String> details = ArgumentCaptor.forClass(String.class);
        verify(auditService).record(eq(admin), eq("WARD_UPDATED"), eq("WARD"), eq(3L), details.capture());
        assertThat(details.getValue()).contains("\"boundary_changed\":true").contains("previous_boundary_geojson");
    }

    @Test
    void fallbackDepartmentCannotBeRenamedOrDeactivated() {
        Department fallback = Department.builder().departmentId(9L).name("General Triage").isActive(true).build();
        when(departmentRepository.findById(9L)).thenReturn(Optional.of(fallback));
        assertThatThrownBy(() -> departments.update(admin, 9L, new DepartmentUpsertRequest("Triage", null, null)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("fallback");
        assertThatThrownBy(() -> departments.setActive(admin, 9L, false))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("fallback");
    }

    @Test
    void departmentStillTargetedByRoutingRulesCannotBeDeactivated() {
        Department roads = Department.builder().departmentId(1L).name("Roads").isActive(true).build();
        when(departmentRepository.findById(1L)).thenReturn(Optional.of(roads));
        when(routingRuleRepository.existsByDepartment_DepartmentIdAndIsActiveTrue(1L)).thenReturn(true);
        assertThatThrownBy(() -> departments.setActive(admin, 1L, false)).hasMessageContaining("routing rules");
    }

    @Test
    void headMustBeAnActiveDepartmentHeadOfThatDepartment() {
        Department roads = Department.builder().departmentId(1L).name("Roads").isActive(true).build();
        Department water = Department.builder().departmentId(2L).name("Water").isActive(true).build();
        when(departmentRepository.findById(1L)).thenReturn(Optional.of(roads));
        User wrongDept = User.builder().userId(7L).role(Role.DEPARTMENT_HEAD).status(UserStatus.ACTIVE).department(water).build();
        User officer = User.builder().userId(8L).role(Role.GOVERNMENT_OFFICER).status(UserStatus.ACTIVE).department(roads).build();
        User head = User.builder().userId(9L).role(Role.DEPARTMENT_HEAD).status(UserStatus.ACTIVE).department(roads).build();
        when(userRepository.findById(7L)).thenReturn(Optional.of(wrongDept));
        when(userRepository.findById(8L)).thenReturn(Optional.of(officer));
        when(userRepository.findById(9L)).thenReturn(Optional.of(head));

        assertThatThrownBy(() -> departments.update(admin, 1L, new DepartmentUpsertRequest("Roads", null, 7L)))
                .hasMessageContaining("belong");
        assertThatThrownBy(() -> departments.update(admin, 1L, new DepartmentUpsertRequest("Roads", null, 8L)))
                .hasMessageContaining("DEPARTMENT_HEAD");
        assertThat(departments.update(admin, 1L, new DepartmentUpsertRequest("Roads", "Road repair", 9L)).headUserId())
                .isEqualTo(9L);
        verify(auditService).record(eq(admin), eq("DEPARTMENT_UPDATED"), eq("DEPARTMENT"), anyLong(), anyString());
    }

    @Test
    void createRejectsAHeadAndSavesTheDepartment() {
        assertThatThrownBy(() -> departments.create(admin, new DepartmentUpsertRequest("Parks", null, 5L)))
                .isInstanceOf(IllegalArgumentException.class);
        var created = departments.create(admin, new DepartmentUpsertRequest("Parks", "Parks and gardens", null));
        assertThat(created.name()).isEqualTo("Parks");
        assertThat(created.isActive()).isTrue();
    }
}
