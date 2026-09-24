package com.jannetai.backend.exception;

/** SRS 15.2: 5 consecutive failed logins locks the account for 15 minutes. */
public class AccountLockedException extends RuntimeException {
    public AccountLockedException(String message) {
        super(message);
    }
}
