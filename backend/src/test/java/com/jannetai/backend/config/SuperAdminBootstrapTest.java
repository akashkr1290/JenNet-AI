package com.jannetai.backend.config;

import com.jannetai.backend.entity.User;
import com.jannetai.backend.entity.enums.Role;
import com.jannetai.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** BOOTSTRAP_SUPER_ADMIN_EMAIL: set on a new Super Admin, filled in (never overwritten) on an existing one. */
@ExtendWith(MockitoExtension.class)
class SuperAdminBootstrapTest {

    private static final String MOBILE = "9876543210";
    private static final String EMAIL = "admin@example.org";

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @InjectMocks private SuperAdminBootstrap bootstrap;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(bootstrap, "mobileNumber", MOBILE);
        ReflectionTestUtils.setField(bootstrap, "password", "a-strong-password");
        ReflectionTestUtils.setField(bootstrap, "fullName", "System Administrator");
    }

    @Test
    void newSuperAdminGetsTheVerifiedBootstrapEmail() {
        ReflectionTestUtils.setField(bootstrap, "email", EMAIL);
        when(userRepository.existsByMobileNumber(MOBILE)).thenReturn(false);
        when(userRepository.existsByEmail(EMAIL)).thenReturn(false);
        when(passwordEncoder.encode("a-strong-password")).thenReturn("hash");

        bootstrap.run();

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getRole()).isEqualTo(Role.SUPER_ADMIN);
        assertThat(saved.getValue().getEmail()).isEqualTo(EMAIL);
        assertThat(saved.getValue().getEmailVerifiedAt()).isNotNull();
    }

    @Test
    void newSuperAdminWithoutBootstrapEmailHasNoEmail() {
        when(userRepository.existsByMobileNumber(MOBILE)).thenReturn(false);
        when(passwordEncoder.encode("a-strong-password")).thenReturn("hash");

        bootstrap.run();

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getEmail()).isNull();
    }

    @Test
    void existingSuperAdminWithoutEmailGetsItFilledIn() {
        ReflectionTestUtils.setField(bootstrap, "email", EMAIL);
        User existing = User.builder().mobileNumber(MOBILE).role(Role.SUPER_ADMIN).build();
        when(userRepository.existsByMobileNumber(MOBILE)).thenReturn(true);
        when(userRepository.findByMobileNumber(MOBILE)).thenReturn(Optional.of(existing));
        when(userRepository.existsByEmail(EMAIL)).thenReturn(false);

        bootstrap.run();

        verify(userRepository).save(existing);
        assertThat(existing.getEmail()).isEqualTo(EMAIL);
        assertThat(existing.getEmailVerifiedAt()).isNotNull();
    }

    @Test
    void existingEmailIsNeverOverwritten() {
        ReflectionTestUtils.setField(bootstrap, "email", EMAIL);
        User existing = User.builder().mobileNumber(MOBILE).email("real@example.org").build();
        when(userRepository.existsByMobileNumber(MOBILE)).thenReturn(true);
        when(userRepository.findByMobileNumber(MOBILE)).thenReturn(Optional.of(existing));

        bootstrap.run();

        verify(userRepository, never()).save(any());
        assertThat(existing.getEmail()).isEqualTo("real@example.org");
    }

    @Test
    void emailUsedByAnotherAccountIsNotAdded() {
        ReflectionTestUtils.setField(bootstrap, "email", EMAIL);
        User existing = User.builder().mobileNumber(MOBILE).build();
        when(userRepository.existsByMobileNumber(MOBILE)).thenReturn(true);
        when(userRepository.findByMobileNumber(MOBILE)).thenReturn(Optional.of(existing));
        when(userRepository.existsByEmail(EMAIL)).thenReturn(true);

        bootstrap.run();

        verify(userRepository, never()).save(any());
        assertThat(existing.getEmail()).isNull();
    }
}
