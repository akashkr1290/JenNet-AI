package com.jannetai.backend.exception;

/**
 * SRS 15.2 Exceptions: "suspended accounts receive a specific 'account
 * suspended' error rather than generic authentication failure."
 */
public class AccountSuspendedException extends RuntimeException {
    public AccountSuspendedException(String message) {
        super(message);
    }
}
