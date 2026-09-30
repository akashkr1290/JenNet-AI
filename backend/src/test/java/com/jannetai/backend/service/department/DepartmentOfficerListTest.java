package com.jannetai.backend.service.department;

import com.jannetai.backend.dto.department.AssignableOfficerResponse;
import com.jannetai.backend.entity.Setting;
import com.jannetai.backend.entity.enums.SettingScope;
import com.jannetai.backend.entity.Department;
import com.jannetai.backend.entity.User;
import com.jannetai.backend.entity.enums.Role;
import com.jannetai.backend.entity.enums.UserStatus;
import com.jannetai.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** Pilot 2026-09-30: the Reassign dialog only offers officers who can actually sign in. */
@ExtendWith(MockitoExtension.class)
class DepartmentOfficerListTest {

    @Mock private UserRepository userRepository;
    @Mock private com.jannetai.backend.repository.ComplaintRepository complaintRepository;
    @Mock private com.jannetai.backend.repository.DepartmentRepository departmentRepository;
    @Mock private com.jannetai.backend.repository.SettingRepository settingRepository;
    @InjectMocks private DepartmentPerformanceService service;

    @Test
    void suspendedOfficersAreNotOffered() {
        Department dept = Department.builder().departmentId(1L).name("Public Works").build();
        User head = User.builder().userId(10L).role(Role.DEPARTMENT_HEAD).department(dept).build();
        User active = User.builder().userId(20L).fullName("Asha").role(Role.GOVERNMENT_OFFICER)
                .status(UserStatus.ACTIVE).department(dept).reputationScore(0).build();
        User suspended = User.builder().userId(21L).fullName("Bala").role(Role.GOVERNMENT_OFFICER)
                .status(UserStatus.SUSPENDED).department(dept).reputationScore(0).build();
        when(userRepository.findByRoleAndDepartment_DepartmentIdOrderByFullNameAsc(Role.GOVERNMENT_OFFICER, 1L))
                .thenReturn(List.of(active, suspended));

        List<AssignableOfficerResponse> officers = service.listOfficers(head, 1L);

        assertThat(officers).extracting(AssignableOfficerResponse::userId).containsExactly(20L);
    }

    @Test
    void theHeadSeesEachOfficersAvailabilityAndOpenWorkload() {
        // Pilot workflow 2026-09-30: the Department Head assigns officers, so the
        // picker shows what automatic load balancing used to decide on.
        Department dept = Department.builder().departmentId(1L).name("Public Works").build();
        User head = User.builder().userId(10L).role(Role.DEPARTMENT_HEAD).department(dept).build();
        User asha = User.builder().userId(20L).fullName("Asha").role(Role.GOVERNMENT_OFFICER)
                .status(UserStatus.ACTIVE).department(dept).build();
        User bala = User.builder().userId(21L).fullName("Bala").role(Role.GOVERNMENT_OFFICER)
                .status(UserStatus.ACTIVE).department(dept).build();
        when(userRepository.findByRoleAndDepartment_DepartmentIdOrderByFullNameAsc(Role.GOVERNMENT_OFFICER, 1L))
                .thenReturn(List.of(asha, bala));
        when(settingRepository.findByScopeAndKeyAndScopeIdIn(any(), any(), any())).thenReturn(List.of(
                Setting.builder().scope(SettingScope.USER).scopeId(21L).key("officer_availability_status")
                        .value("on_leave").build()));
        when(complaintRepository.countByAssignedOfficer_UserIdAndStatusIn(eq(20L), any())).thenReturn(3L);
        when(complaintRepository.countByAssignedOfficer_UserIdAndStatusIn(eq(21L), any())).thenReturn(0L);

        List<AssignableOfficerResponse> officers = service.listOfficers(head, 1L);

        assertThat(officers).extracting(AssignableOfficerResponse::availability).containsExactly("AVAILABLE", "ON_LEAVE");
        assertThat(officers).extracting(AssignableOfficerResponse::openComplaints).containsExactly(3L, 0L);
    }
}
