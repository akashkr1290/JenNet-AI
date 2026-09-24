package com.jannetai.backend.exception;

/** SRS 15.1 anti-spam control: "a citizen may submit a maximum of 10 complaints per rolling 24-hour period". Mapped to 429 Too Many Requests. */
public class ComplaintLimitExceededException extends RuntimeException {
    public ComplaintLimitExceededException(String message) {
        super(message);
    }
}
