package com.jannetai.backend.security;

import com.jannetai.backend.entity.User;
import com.jannetai.backend.entity.enums.Role;
import com.jannetai.backend.entity.enums.UserStatus;
import com.jannetai.backend.repository.UserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link JwtAuthenticationFilter} - the stateless bearer-
 * token auth filter (SRS 27.1). {@code SecurityContextHolder} is cleared
 * before/after each test since it's a ThreadLocal this filter itself
 * mutates as a side effect.
 *
 * NOT EXECUTED in this workspace (no Maven Central reach - see
 * PROJECT_PROGRESS.md's Phase 20 TESTS section). Manually validated
 * against JwtAuthenticationFilter.java's actual header/claim handling.
 */
@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    @Mock private JwtService jwtService;
    @Mock private UserRepository userRepository;
    @Mock private FilterChain filterChain;

    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new JwtAuthenticationFilter(jwtService, userRepository);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private User activeUser() {
        return User.builder().userId(1L).role(Role.CITIZEN).status(UserStatus.ACTIVE)
                .fullName("Test User").mobileNumber("+911234567890").build();
    }

    @Test
    void validTokenForActiveUserSetsAuthentication() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        Claims claims = mockClaims(1L);
        when(jwtService.parseAndValidate("valid-token")).thenReturn(claims);
        when(userRepository.findById(1L)).thenReturn(Optional.of(activeUser()));

        filter.doFilter(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().isAuthenticated()).isTrue();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void missingAuthorizationHeaderLeavesContextEmpty() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void headerWithoutBearerPrefixIsIgnored() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Basic somecreds");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void invalidTokenLeavesContextEmptyRatherThanThrowing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer garbage");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(jwtService.parseAndValidate("garbage")).thenThrow(new JwtException("bad token"));

        filter.doFilter(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        // Chain must still continue - SecurityConfig's own rules produce the 401.
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void suspendedUserIsNotAuthenticatedEvenWithAValidToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        User suspended = activeUser();
        suspended.setStatus(UserStatus.SUSPENDED);
        when(jwtService.parseAndValidate("valid-token")).thenReturn(mockClaims(1L));
        when(userRepository.findById(1L)).thenReturn(Optional.of(suspended));

        filter.doFilter(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void lockedUserIsNotAuthenticatedEvenWithAValidToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        User locked = activeUser();
        locked.setLockedUntil(LocalDateTime.now().plusMinutes(10));
        when(jwtService.parseAndValidate("valid-token")).thenReturn(mockClaims(1L));
        when(userRepository.findById(1L)).thenReturn(Optional.of(locked));

        filter.doFilter(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void tokenForANonexistentUserIsNotAuthenticated() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(jwtService.parseAndValidate("valid-token")).thenReturn(mockClaims(999L));
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        filter.doFilter(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void existingAuthenticationIsNotOverwritten() throws Exception {
        // Guards the `getAuthentication() == null` check - if some earlier
        // filter already authenticated the request, this filter must not
        // clobber it (e.g. re-parsing an already-consumed token).
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        var existing = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                "someone-else", null, java.util.List.of());
        SecurityContextHolder.getContext().setAuthentication(existing);

        filter.doFilter(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(existing);
        verify(jwtService, org.mockito.Mockito.never()).parseAndValidate(any());
    }

    private Claims mockClaims(Long userId) {
        Claims claims = org.mockito.Mockito.mock(Claims.class);
        when(claims.get(eq("uid"), eq(Long.class))).thenReturn(userId);
        return claims;
    }
}
