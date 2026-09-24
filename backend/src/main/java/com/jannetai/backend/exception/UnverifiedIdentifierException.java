package com.jannetai.backend.exception;

/** SRS 15.1: "a citizen must verify their mobile number or email before submitting a first complaint". Mapped to 403 Forbidden. */
public class UnverifiedIdentifierException extends RuntimeException {
    public UnverifiedIdentifierException(String message) {
        super(message);
    }
}
