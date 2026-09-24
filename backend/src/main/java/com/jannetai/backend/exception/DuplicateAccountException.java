package com.jannetai.backend.exception;

/** SRS 18: POST /auth/register -> 409 Conflict on duplicate mobile/email. */
public class DuplicateAccountException extends RuntimeException {
    public DuplicateAccountException(String message) {
        super(message);
    }
}
