package com.jannetai.backend.controller;

import com.jannetai.backend.dto.auth.*;
import com.jannetai.backend.exception.MfaRequiredException;
import com.jannetai.backend.service.AuthService;
import com.jannetai.backend.security.ClientIpResolver;
import com.jannetai.backend.security.UserPrincipal;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Authentication Module (SRS 15.2 / Section 18). Endpoint paths and
 * status codes match the SRS's Authentication APIs table exactly for the
 * four "representative" endpoints (register/verify-otp/login/refresh);
 * resend-otp, mfa/verify, logout, forgot-password, and reset-password are
 * additions required to make the documented business rules (MFA, password
 * reset via OTP, resendable OTP, revocable sessions) actually reachable.
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "Registration, OTP verification, login, MFA, token refresh, and password reset")
public class AuthController {

    private final AuthService authService;
    private final ClientIpResolver clientIpResolver;

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserProfileResponse register(@Valid @RequestBody RegisterRequest request, HttpServletRequest http) {
        return authService.register(request, clientIp(http));
    }

    @PostMapping("/verify-otp")
    public SimpleMessageResponse verifyOtp(@Valid @RequestBody VerifyOtpRequest request) {
        authService.verifyRegistrationOtp(request);
        return new SimpleMessageResponse("Mobile number verified");
    }

    @PostMapping("/resend-otp")
    public SimpleMessageResponse resendOtp(@Valid @RequestBody ResendOtpRequest request, HttpServletRequest http) {
        authService.resendOtp(request, clientIp(http));
        return new SimpleMessageResponse("OTP resent if the account exists");
    }

    /**
     * Returns 200 with either an AuthResponse (tokens) or an
     * MfaRequiredResponse (Admin/Super Admin) - both are 200 because
     * credentials WERE valid in both cases; only the response shape
     * differs. Flutter distinguishes by the presence of "mfaRequired".
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        try {
            AuthResponse response = authService.login(request, deviceLabel(http), clientIp(http));
            return ResponseEntity.ok(response);
        } catch (MfaRequiredException mfa) {
            return ResponseEntity.ok(MfaRequiredResponse.of(mfa.getMfaToken()));
        }
    }

    @PostMapping("/mfa/verify")
    public AuthResponse verifyMfa(@Valid @RequestBody MfaVerifyRequest request, HttpServletRequest http) {
        return authService.verifyMfa(request, deviceLabel(http), clientIp(http));
    }

    @PostMapping("/refresh")
    public AuthResponse refresh(@Valid @RequestBody RefreshRequest request, HttpServletRequest http) {
        return authService.refresh(request, deviceLabel(http), clientIp(http));
    }

    @PostMapping("/logout")
    public SimpleMessageResponse logout(@Valid @RequestBody LogoutRequest request) {
        authService.logout(request);
        return new SimpleMessageResponse("Logged out");
    }

    /** "Logout everywhere" - self-service session revocation (SRS 27.5). */
    @PostMapping("/logout-all")
    @PreAuthorize("isAuthenticated()")
    public SimpleMessageResponse logoutAll(@AuthenticationPrincipal UserPrincipal principal) {
        authService.logoutAllSessions(principal.getUser().getUserId());
        return new SimpleMessageResponse("Logged out of all sessions");
    }

    @PostMapping("/forgot-password")
    public SimpleMessageResponse forgotPassword(@Valid @RequestBody ForgotPasswordRequest request, HttpServletRequest http) {
        authService.forgotPassword(request, clientIp(http));
        // Deliberately generic wording - do not confirm/deny account existence.
        return new SimpleMessageResponse("If an account exists for this number, an OTP has been sent");
    }

    @PostMapping("/reset-password")
    public SimpleMessageResponse resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
        return new SimpleMessageResponse("Password has been reset; please log in again");
    }

    private String clientIp(HttpServletRequest request) {
        // Audit GAP-049: X-Forwarded-For is client-controlled; use the trusted
        // proxy header only (see ClientIpResolver).
        return clientIpResolver.resolve(request);
    }

    private String deviceLabel(HttpServletRequest request) {
        String ua = request.getHeader("User-Agent");
        return ua != null ? ua : "unknown-device";
    }
}
