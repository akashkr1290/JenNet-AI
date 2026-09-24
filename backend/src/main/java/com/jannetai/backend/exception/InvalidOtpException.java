package com.jannetai.backend.exception;

/** Wrong/expired/exhausted OTP code (SRS 15.2: "400 Invalid/expired OTP"). */
public class InvalidOtpException extends RuntimeException {
    public InvalidOtpException(String message) {
        super(message);
    }
}
