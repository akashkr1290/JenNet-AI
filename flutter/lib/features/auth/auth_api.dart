import '../../core/api/api_client.dart';
import '../../core/auth/token_storage.dart';

/// Result of [AuthApi.login]. Exactly one of two shapes per
/// AuthController.login's own Javadoc ("both are 200 because credentials
/// WERE valid in both cases; only the response shape differs"):
/// - Non-MFA account (Citizen/Officer/Department Head): tokens are already
///   saved by the time this returns, [mfaToken] is null.
/// - MFA account (Admin/Super Admin, SRS 27.1): no tokens yet, [mfaToken]
///   is the short-lived token the caller must now pass to
///   [AuthApi.verifyMfa] along with the OTP that was just sent.
class LoginResult {
  final String? mfaToken;
  const LoginResult({this.mfaToken});
  bool get mfaRequired => mfaToken != null;
}

/// Full Authentication Module (SRS 15.2 / Section 18) integration - Phase
/// 17. Phase 6 shipped only [login] (the bare minimum to unlock the
/// Complaint Module screens it actually owned that phase; see this
/// class's Phase 6 history in PROJECT_INTEGRATION.md Section 6).
/// Registration, OTP verification/resend, MFA verification, and password
/// reset were explicitly deferred to "the Authentication Module's own
/// frontend" - this phase is that frontend. Every method here calls a
/// pre-existing, unmodified `AuthController` endpoint; no backend change
/// was needed (see PROJECT_INTEGRATION.md Section 6, Phase 17 entry).
class AuthApi {
  AuthApi._();
  static final AuthApi instance = AuthApi._();

  final _client = ApiClient.instance;
  final _tokens = TokenStorage.instance;

  /// POST /auth/register (SRS Screen 16.1 Registration). The created
  /// account's mobile number is already known to the caller (it's an
  /// input), so the response body isn't surfaced further than success/
  /// failure - RegisterScreen only needs that before moving to OTP
  /// verification.
  ///
  /// [wardId] comes from RegisterScreen's ward picker, which loads the
  /// public GET /api/v1/public/wards list (post-UI gap fix). It stays
  /// nullable - RegisterRequest.wardId has no @NotNull - so registration
  /// still works if the ward list cannot be loaded.
  Future<void> register({
    required String fullName,
    required String mobileNumber,
    String? email,
    required String password,
    int? wardId,
    String otpChannel = 'SMS',
  }) async {
    await _client.post('/auth/register', body: {
      'fullName': fullName,
      'mobileNumber': mobileNumber,
      if (email != null && email.isNotEmpty) 'email': email,
      'password': password,
      if (wardId != null) 'wardId': wardId,
      // Audit GAP-004: SMS (default) or EMAIL for the verification code.
      'otpChannel': otpChannel,
    });
  }

  /// POST /auth/verify-otp (purpose=REGISTRATION implicitly - this is the
  /// only OTP-verify endpoint the SRS defines; it is not purpose-
  /// parameterized the way resend-otp is).
  Future<void> verifyOtp({required String mobileNumber, required String otpCode}) async {
    await _client.post('/auth/verify-otp', body: {
      'mobileNumber': mobileNumber,
      'otpCode': otpCode,
    });
  }

  /// POST /auth/resend-otp. [purpose] must be one of the backend's
  /// `OtpPurpose` enum names exactly: REGISTRATION, LOGIN_MFA,
  /// PASSWORD_RESET.
  /// [channel] (audit GAP-004): SMS (default) or EMAIL; the backend ignores
  /// it for LOGIN_MFA, which is always SMS.
  Future<void> resendOtp({required String mobileNumber, required String purpose, String channel = 'SMS'}) async {
    await _client.post('/auth/resend-otp', body: {
      'mobileNumber': mobileNumber,
      'purpose': purpose,
      'channel': channel,
    });
  }

  /// POST /auth/login. Saves tokens and returns a non-MFA-required
  /// [LoginResult] for Citizen/Officer/Department Head accounts; returns
  /// an [LoginResult.mfaRequired] result (no tokens saved yet) for
  /// Admin/Super Admin accounts, which the caller must complete via
  /// [verifyMfa]. Phase 6's StateError-on-MFA behavior is removed - this
  /// is exactly the gap this phase closes (Admin/Super Admin could not
  /// previously log in through the app at all).
  Future<LoginResult> login({required String identifier, required String password}) async {
    final json = await _client.post('/auth/login', body: {
      'identifier': identifier,
      'password': password,
    }) as Map<String, dynamic>;

    if (json['mfaRequired'] == true) {
      return LoginResult(mfaToken: json['mfaToken'] as String);
    }

    await _tokens.saveTokens(
      accessToken: json['accessToken'] as String,
      refreshToken: json['refreshToken'] as String,
    );
    return const LoginResult();
  }

  /// POST /auth/mfa/verify - second step of login for Admin/Super Admin
  /// (SRS 27.1). Saves tokens on success.
  Future<void> verifyMfa({required String mfaToken, required String otpCode}) async {
    final json = await _client.post('/auth/mfa/verify', body: {
      'mfaToken': mfaToken,
      'otpCode': otpCode,
    }) as Map<String, dynamic>;

    await _tokens.saveTokens(
      accessToken: json['accessToken'] as String,
      refreshToken: json['refreshToken'] as String,
    );
  }

  Future<bool> get isLoggedIn async => (await _tokens.accessToken) != null;

  /// POST /auth/forgot-password (SRS Screen 16.1 "Forgot Password"
  /// button). Deliberately never surfaces whether the account exists -
  /// AuthController's own response wording is generic by design; callers
  /// should always proceed to the reset-password screen regardless.
  Future<void> forgotPassword({required String mobileNumber}) async {
    await _client.post('/auth/forgot-password', body: {'mobileNumber': mobileNumber});
  }

  /// POST /auth/reset-password. On success the account's refresh tokens
  /// are invalidated server-side (AuthService's own behavior, unchanged
  /// by this phase) - the caller must log in again with the new password.
  Future<void> resetPassword({
    required String mobileNumber,
    required String otpCode,
    required String newPassword,
  }) async {
    await _client.post('/auth/reset-password', body: {
      'mobileNumber': mobileNumber,
      'otpCode': otpCode,
      'newPassword': newPassword,
    });
  }

  /// Phase 17: now actually calls POST /auth/logout (revokes this
  /// device's refresh-token family server-side, per LogoutRequest's
  /// Javadoc) before clearing local storage. Phase 6-16 callers of this
  /// method are unaffected - the signature and "always ends up logged
  /// out locally" contract are unchanged; only the server-side revocation
  /// is new. The backend call is best-effort: a network failure or an
  /// already-expired/invalid refresh token must never prevent the local
  /// logout the four existing call sites (AdminHomeScreen,
  /// ComplaintHomeScreen, DepartmentHeadHomeScreen, OfficerHomeScreen)
  /// depend on.
  Future<void> logout() async {
    final refreshToken = await _tokens.refreshToken;
    if (refreshToken != null) {
      try {
        await _client.post('/auth/logout', body: {'refreshToken': refreshToken});
      } catch (_) {
        // Best-effort - see doc comment above.
      }
    }
    await _tokens.clear();
  }

  /// POST /auth/logout-all (SRS 27.5 "logout everywhere" self-service
  /// session revocation) - new Phase 17 entry point, wired into
  /// PersonalSettingsScreen's new Account section. Revokes every
  /// session's refresh token server-side, then clears this device's local
  /// tokens too (this device is one of the sessions being revoked).
  Future<void> logoutAll() async {
    try {
      await _client.post('/auth/logout-all');
    } catch (_) {
      // Best-effort, same reasoning as logout() above - a stale/expired
      // access token on this call must not block the local logout.
    }
    await _tokens.clear();
  }
}
