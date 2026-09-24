package com.jannetai.backend.exception;

/**
 * Thrown by ComplaintStateMachine/ComplaintService when a requested status
 * transition isn't in the locked transition table (ARCHITECTURE.md
 * Section 4), the acting role isn't permitted to perform it, or a
 * decision-specific required field is missing (e.g. rejectionReasonCode
 * on a REJECTED verification decision). Mapped to 400 Bad Request by
 * GlobalExceptionHandler, matching SRS Table 23's documented
 * "400 Invalid transition" response for PATCH .../status.
 */
public class InvalidStateTransitionException extends RuntimeException {
    public InvalidStateTransitionException(String message) {
        super(message);
    }
}
