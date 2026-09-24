package com.jannetai.backend.exception;

/** SRS Table 23 (/reopen): "409 Conflict (grace period expired)". */
public class GracePeriodExpiredException extends RuntimeException {
    public GracePeriodExpiredException(String message) {
        super(message);
    }
}
