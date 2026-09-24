package com.jannetai.backend.dto.auth;

/**
 * 200 OK body from /login when the account's role requires MFA (SRS
 * 27.1). No tokens are issued yet - the client must call /mfa/verify with
 * mfaToken and the OTP just sent.
 */
public record MfaRequiredResponse(
        boolean mfaRequired,
        String mfaToken
) {
    public static MfaRequiredResponse of(String mfaToken) {
        return new MfaRequiredResponse(true, mfaToken);
    }
}
