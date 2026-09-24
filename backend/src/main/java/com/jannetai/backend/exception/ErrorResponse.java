package com.jannetai.backend.exception;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Standard error response shape locked in PROJECT_INTEGRATION.md Section 2
 * (finalized this phase). Every non-2xx JSON response from this backend
 * must use this shape so Flutter has exactly one error format to parse.
 */
public record ErrorResponse(
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        OffsetDateTime timestamp,
        int status,
        String error,
        String message,
        String path,
        List<String> details
) {
    public static ErrorResponse of(int status, String error, String message, String path) {
        return new ErrorResponse(OffsetDateTime.now(), status, error, message, path, List.of());
    }

    public static ErrorResponse of(int status, String error, String message, String path, List<String> details) {
        return new ErrorResponse(OffsetDateTime.now(), status, error, message, path, details);
    }
}
