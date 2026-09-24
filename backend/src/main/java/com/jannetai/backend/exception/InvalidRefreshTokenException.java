package com.jannetai.backend.exception;

/**
 * Expired, unknown, already-rotated, or reused refresh token (SRS 15.2 /
 * 27.5). Reuse of an already-rotated token additionally revokes the whole
 * token family as a side effect (handled in AuthService, not here).
 */
public class InvalidRefreshTokenException extends RuntimeException {
    public InvalidRefreshTokenException(String message) {
        super(message);
    }
}
