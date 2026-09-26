package com.jannetai.backend.service.privacy;

import com.jannetai.backend.entity.User;
import com.jannetai.backend.entity.enums.Role;
import com.jannetai.backend.entity.enums.SettingScope;
import com.jannetai.backend.entity.enums.UserStatus;
import com.jannetai.backend.repository.ComplaintAppealRepository;
import com.jannetai.backend.repository.ComplaintRatingRepository;
import com.jannetai.backend.repository.ComplaintRepository;
import com.jannetai.backend.repository.DeviceTokenRepository;
import com.jannetai.backend.repository.OtpVerificationRepository;
import com.jannetai.backend.repository.RefreshTokenRepository;
import com.jannetai.backend.repository.SettingRepository;
import com.jannetai.backend.repository.UserRepository;
import com.jannetai.backend.service.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Audit GAP-041 (SRS 24 access / erasure). NOT EXECUTED here via Maven. */
@ExtendWith(MockitoExtension.class)
class PersonalDataServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private ComplaintRepository complaintRepository;
    @Mock private ComplaintAppealRepository complaintAppealRepository;
    @Mock private ComplaintRatingRepository complaintRatingRepository;
    @Mock private SettingRepository settingRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private DeviceTokenRepository deviceTokenRepository;
    @Mock private OtpVerificationRepository otpVerificationRepository;
    @Mock private AuditService auditService;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private PersonalDataService service;
    private User citizen;

    @BeforeEach
    void setUp() {
        service = new PersonalDataService(userRepository, complaintRepository, complaintAppealRepository,
                complaintRatingRepository, settingRepository, refreshTokenRepository, deviceTokenRepository,
                otpVerificationRepository, encoder, auditService);
        citizen = User.builder().userId(42L).role(Role.CITIZEN).status(UserStatus.ACTIVE).fullName("Asha Rao")
                .mobileNumber("9876543210").email("asha@example.com").passwordHash(encoder.encode("Str0ng!Pass"))
                .reputationScore(100).build();
    }

    @Test
    void selfServiceErasureRemovesPersonalDataAndClosesTheAccount() {
        when(userRepository.findById(42L)).thenReturn(Optional.of(citizen));

        service.eraseOwnAccount(citizen, "Str0ng!Pass");

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        User u = saved.getValue();
        assertThat(u.getFullName()).isEqualTo("Erased user");
        assertThat(u.getMobileNumber()).isEqualTo("ERASED42");
        assertThat(u.getEmail()).isNull();
        assertThat(u.getStatus()).isEqualTo(UserStatus.SUSPENDED);
        assertThat(u.getErasedAt()).isNotNull();
        assertThat(encoder.matches("Str0ng!Pass", u.getPasswordHash())).isFalse();
        verify(refreshTokenRepository).revokeAllForUser(eq(42L), any());
        verify(deviceTokenRepository).deleteByUser_UserId(42L);
        verify(otpVerificationRepository).deleteByMobileNumber("9876543210");
        verify(settingRepository).deleteByScopeAndScopeId(SettingScope.USER, 42L);
        ArgumentCaptor<String> details = ArgumentCaptor.forClass(String.class);
        verify(auditService).record(eq(citizen), eq("USER_PERSONAL_DATA_ERASED"), eq("USER"), eq(42L), details.capture());
        assertThat(details.getValue()).doesNotContain("9876543210").doesNotContain("asha");
    }

    @Test
    void wrongPasswordStaffAndRepeatedErasureAreRefused() {
        when(userRepository.findById(42L)).thenReturn(Optional.of(citizen));
        assertThatThrownBy(() -> service.eraseOwnAccount(citizen, "wrong")).isInstanceOf(ResponseStatusException.class);

        User officer = User.builder().userId(7L).role(Role.GOVERNMENT_OFFICER).build();
        when(userRepository.findById(7L)).thenReturn(Optional.of(officer));
        assertThatThrownBy(() -> service.eraseOwnAccount(officer, "x")).hasMessageContaining("Super Administrator");

        citizen.setErasedAt(java.time.LocalDateTime.now());
        User superAdmin = User.builder().userId(1L).role(Role.SUPER_ADMIN).build();
        assertThatThrownBy(() -> service.eraseOnBehalf(superAdmin, 42L, "REQ-1")).hasMessageContaining("already");
        verify(userRepository, never()).save(any());
    }

    @Test
    void exportContainsTheProfileAndIsAudited() {
        when(userRepository.findById(42L)).thenReturn(Optional.of(citizen));
        var export = service.export(citizen);
        assertThat(export.profile().mobileNumber()).isEqualTo("9876543210");
        assertThat(export.complaints()).isEmpty();
        verify(auditService).record(eq(citizen), eq("PERSONAL_DATA_EXPORTED"), eq("USER"), anyLong(), any());
        assertThat(PersonalDataService.placeholderMobile(123456789L)).hasSizeLessThanOrEqualTo(15);
    }
}
