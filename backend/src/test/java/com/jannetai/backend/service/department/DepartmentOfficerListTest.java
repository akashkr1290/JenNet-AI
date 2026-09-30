package com.jannetai.backend.service.department;

import com.jannetai.backend.dto.auth.UserProfileResponse;
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
import static org.mockito.Mockito.when;

/** Pilot 2026-09-30: the Reassign dialog only offers officers who can actually sign in. */
@ExtendWith(MockitoExtension.class)
class DepartmentOfficerListTest {

    @Mock private UserRepository userRepository;
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

        List<UserProfileResponse> officers = service.listOfficers(head, 1L);

        assertThat(officers).extracting(UserProfileResponse::userId).containsExactly(20L);
    }
}
