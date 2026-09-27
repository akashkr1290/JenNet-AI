package com.jannetai.backend.service.admin;

import com.jannetai.backend.dto.admin.AdminCreateUserRequest;
import com.jannetai.backend.dto.admin.AdminCreateUserResponse;
import com.jannetai.backend.entity.Department;
import com.jannetai.backend.entity.User;
import com.jannetai.backend.entity.enums.Role;
import com.jannetai.backend.entity.enums.UserStatus;
import com.jannetai.backend.repository.DepartmentRepository;
import com.jannetai.backend.repository.UserRepository;
import com.jannetai.backend.repository.WardRepository;
import com.jannetai.backend.service.AuditService;
import com.jannetai.backend.service.AuthService;
import com.jannetai.backend.service.notification.DeliveryOutcome;
import com.jannetai.backend.service.notification.EmailGatewayClient;
import com.jannetai.backend.service.notification.NotificationDeliveryException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.RecordComponent;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Staff temporary-password delivery (live pilot bug: a Government Officer
 * account ended up ACTIVE with a password nobody had, and "Reset Password"
 * sent an SMS OTP while SMS was disabled). The temporary password is now
 * e-mailed to the staff member and never returned by the API.
 */
@ExtendWith(MockitoExtension.class)
class AdminUserServiceTest {

    private static final String OFFICER_EMAIL = "officer@example.org";

    @Mock private UserRepository userRepository;
    @Mock private DepartmentRepository departmentRepository;
    @Mock private WardRepository wardRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private AuditService auditService;
    @Mock private AuthService authService;
    @Mock private EmailGatewayClient emailGatewayClient;

    private AdminUserService service;
    private final User superAdmin = User.builder().userId(1L).role(Role.SUPER_ADMIN).fullName("Root").build();
    private final Department publicWorks = Department.builder().departmentId(1L).name("Public Works").build();

    @BeforeEach
    void setUp() {
        service = new AdminUserService(userRepository, departmentRepository, wardRepository,
                passwordEncoder, auditService, authService, emailGatewayClient);
        lenient().when(passwordEncoder.encode(anyString())).thenAnswer(inv -> "HASH(" + inv.getArgument(0) + ")");
        lenient().when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            if (u.getUserId() == null) u.setUserId(42L);
            return u;
        });
        lenient().when(emailGatewayClient.isConfigured()).thenReturn(true);
        lenient().when(emailGatewayClient.send(anyString(), anyString(), anyString())).thenReturn(DeliveryOutcome.SENT);
        lenient().when(departmentRepository.findById(1L)).thenReturn(Optional.of(publicWorks));
    }

    private static AdminCreateUserRequest request(Role role, String email, Long departmentId) {
        return new AdminCreateUserRequest("Demo Officer PWD", "9000000301", email, role, departmentId, null);
    }

    /** The password that was hashed must be exactly the one e-mailed - otherwise nobody could sign in. */
    private String hashedPassword() {
        ArgumentCaptor<String> raw = ArgumentCaptor.forClass(String.class);
        verify(passwordEncoder).encode(raw.capture());
        return raw.getValue();
    }

    private String emailedBody(String to) {
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailGatewayClient).send(eq(to), anyString(), body.capture());
        return body.getValue();
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"GOVERNMENT_OFFICER", "VERIFICATION_TEAM"})
    void createStaffEmailsTheTemporaryPasswordForEveryStaffRole(Role role) {
        Long dept = role == Role.GOVERNMENT_OFFICER ? 1L : null;

        AdminCreateUserResponse response = service.createStaff(superAdmin, request(role, OFFICER_EMAIL, dept));

        String password = hashedPassword();
        assertThat(password).matches("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[!@#$%^&*]).{12}$");
        assertThat(emailedBody(OFFICER_EMAIL)).contains(password).contains("9000000301");

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getPasswordHash()).isEqualTo("HASH(" + password + ")");
        assertThat(saved.getValue().getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(saved.getValue().getRole()).isEqualTo(role);

        assertThat(response.user().role()).isEqualTo(role);
        assertThat(response.user().departmentId()).isEqualTo(dept);
        assertThat(response.passwordDeliveredTo()).isEqualTo("o***@example.org");
        assertThat(response.toString()).doesNotContain(password);
    }

    @Test
    void createResponseHasNoPasswordField() {
        assertThat(Arrays.stream(AdminCreateUserResponse.class.getRecordComponents()).map(RecordComponent::getName))
                .containsExactly("user", "passwordDeliveredTo");
    }

    @Test
    void createStaffWithoutEmailIsRefusedBeforeAnythingIsSaved() {
        assertThatThrownBy(() -> service.createStaff(superAdmin, request(Role.GOVERNMENT_OFFICER, " ", 1L)))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getReason()).contains("e-mail address is required");
                });
        verify(userRepository, never()).save(any());
        verify(emailGatewayClient, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void createStaffIsRefusedWhenEmailDeliveryIsNotConfigured() {
        when(emailGatewayClient.isConfigured()).thenReturn(false);

        assertThatThrownBy(() -> service.createStaff(superAdmin, request(Role.GOVERNMENT_OFFICER, OFFICER_EMAIL, 1L)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        verify(userRepository, never()).save(any());
    }

    @Test
    void createStaffFailsWhenTheMailServerRejectsTheMessage() {
        // The exception rolls back @Transactional createStaff, so no account is
        // left behind with a password nobody received.
        when(emailGatewayClient.send(anyString(), anyString(), anyString()))
                .thenThrow(new NotificationDeliveryException("smtp down"));

        assertThatThrownBy(() -> service.createStaff(superAdmin, request(Role.GOVERNMENT_OFFICER, OFFICER_EMAIL, 1L)))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.getReason()).contains("could not be sent");
                });
    }

    @Test
    void createStaffFailsWhenTheMessageWasNotSent() {
        when(emailGatewayClient.send(anyString(), anyString(), anyString())).thenReturn(DeliveryOutcome.SKIPPED);

        assertThatThrownBy(() -> service.createStaff(superAdmin, request(Role.GOVERNMENT_OFFICER, OFFICER_EMAIL, 1L)))
                .isInstanceOf(ResponseStatusException.class);
    }

    private User existingOfficer(String email) {
        return User.builder().userId(7L).role(Role.GOVERNMENT_OFFICER).fullName("Demo Officer PWD")
                .mobileNumber("9000000301").email(email).passwordHash("OLD").status(UserStatus.ACTIVE)
                .failedLoginCount(3).lockedUntil(LocalDateTime.now().plusMinutes(10)).build();
    }

    @Test
    void resetSetsANewTemporaryPasswordAndEmailsIt() {
        User officer = existingOfficer(OFFICER_EMAIL);
        when(userRepository.findById(7L)).thenReturn(Optional.of(officer));

        String deliveredTo = service.triggerPasswordReset(superAdmin, 7L);

        String password = hashedPassword();
        assertThat(officer.getPasswordHash()).isEqualTo("HASH(" + password + ")");
        assertThat(officer.getFailedLoginCount()).isZero();
        assertThat(officer.getLockedUntil()).isNull();
        assertThat(emailedBody(OFFICER_EMAIL)).contains(password);
        assertThat(deliveredTo).isEqualTo("o***@example.org").doesNotContain(password);
        verify(authService).logoutAllSessions(7L);
        verify(auditService).record(superAdmin, "ADMIN_PASSWORD_RESET_TRIGGERED", "USER", 7L, null);
    }

    @Test
    void resetWithoutEmailIsRefusedAndChangesNothing() {
        User officer = existingOfficer(null);
        when(userRepository.findById(7L)).thenReturn(Optional.of(officer));

        assertThatThrownBy(() -> service.triggerPasswordReset(superAdmin, 7L))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getReason()).contains("has no e-mail address");
                });
        assertThat(officer.getPasswordHash()).isEqualTo("OLD");
        verify(userRepository, never()).save(any());
        verifyNoInteractions(authService);
    }

    @Test
    void resetStillRespectsTheRolePrivilegeMatrix() {
        User root = User.builder().userId(9L).role(Role.SUPER_ADMIN).email("root@example.org").build();
        when(userRepository.findById(9L)).thenReturn(Optional.of(root));

        assertThatThrownBy(() -> service.triggerPasswordReset(superAdmin, 9L))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        verifyNoInteractions(emailGatewayClient);
    }
}
