package com.jannetai.backend.exception;

/**
 * Not an error in the normal sense - thrown by AuthService to short-circuit
 * a normal login into the MFA step for Admin/Super Admin (SRS 27.1), caught
 * by AuthController and turned into a 200 {mfa_required: true, ...} body
 * rather than a 4xx, since the credentials WERE valid.
 */
public class MfaRequiredException extends RuntimeException {
    private final String mfaToken;

    public MfaRequiredException(String mfaToken) {
        super("MFA verification required");
        this.mfaToken = mfaToken;
    }

    public String getMfaToken() {
        return mfaToken;
    }
}
