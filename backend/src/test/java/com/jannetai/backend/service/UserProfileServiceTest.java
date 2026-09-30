package com.jannetai.backend.service;

import com.jannetai.backend.dto.auth.UserProfileResponse;
import com.jannetai.backend.entity.Department;
import com.jannetai.backend.entity.User;
import com.jannetai.backend.entity.Ward;
import com.jannetai.backend.entity.enums.Role;
import com.jannetai.backend.entity.enums.UserStatus;
import com.jannetai.backend.exception.ResourceNotFoundException;
import com.jannetai.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/** Pilot 2026-09-30: GET /users/me carries the department and ward names for the app's side bar. */
@ExtendWith(MockitoExtension.class)
class UserProfileServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private WardService wardService;
    @Mock private AuditService auditService;
    @InjectMocks private UserProfileService service;

    private static User user(Department department, Ward ward) {
        return User.builder().userId(7L).fullName("Ravi Kumar").mobileNumber("9000000401")
                .role(Role.DEPARTMENT_HEAD).status(UserStatus.ACTIVE).reputationScore(0)
                .department(department).ward(ward).build();
    }

    @Test
    void meReturnsDepartmentAndWardNames() {
        Department pwd = Department.builder().departmentId(3L).name("Public Works").build();
        Ward ward = Ward.builder().wardId(12L).name("Ward 12 - Sector 18").build();
        User detached = user(null, null);
        when(userRepository.findById(7L)).thenReturn(Optional.of(user(pwd, ward)));

        UserProfileResponse me = service.me(detached);

        assertThat(me.fullName()).isEqualTo("Ravi Kumar");
        assertThat(me.role()).isEqualTo(Role.DEPARTMENT_HEAD);
        assertThat(me.departmentId()).isEqualTo(3L);
        assertThat(me.departmentName()).isEqualTo("Public Works");
        assertThat(me.wardId()).isEqualTo(12L);
        assertThat(me.wardName()).isEqualTo("Ward 12 - Sector 18");
    }

    @Test
    void accountsWithoutDepartmentOrWardHaveNullNames() {
        when(userRepository.findById(7L)).thenReturn(Optional.of(user(null, null)));

        UserProfileResponse me = service.me(user(null, null));

        assertThat(me.departmentName()).isNull();
        assertThat(me.wardName()).isNull();
    }

    @Test
    void deletedAccountIsNotFound() {
        when(userRepository.findById(7L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.me(user(null, null))).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void fromWithoutATransactionLeavesNamesEmpty() {
        Department pwd = Department.builder().departmentId(3L).name("Public Works").build();
        UserProfileResponse r = UserProfileResponse.from(user(pwd, null));
        assertThat(r.departmentId()).isEqualTo(3L);
        assertThat(r.departmentName()).isNull();
    }
}
