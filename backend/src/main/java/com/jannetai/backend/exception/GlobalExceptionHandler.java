package com.jannetai.backend.exception;

import com.jannetai.backend.storage.StorageException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Translates exceptions into the standard error response shape
 * (PROJECT_INTEGRATION.md Section 2). Every controller added by later
 * phases inherits this behavior automatically - no per-controller
 * try/catch needed for these cases.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex,
                                                            HttpServletRequest request) {
        List<String> details = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .toList();
        ErrorResponse body = ErrorResponse.of(
                HttpStatus.BAD_REQUEST.value(),
                "BAD_REQUEST",
                "Validation failed",
                request.getRequestURI(),
                details
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(ResourceNotFoundException ex,
                                                          HttpServletRequest request) {
        ErrorResponse body = ErrorResponse.of(
                HttpStatus.NOT_FOUND.value(),
                "NOT_FOUND",
                ex.getMessage(),
                request.getRequestURI()
        );
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatus(ResponseStatusException ex,
                                                                HttpServletRequest request) {
        ErrorResponse body = ErrorResponse.of(
                ex.getStatusCode().value(),
                ex.getStatusCode().toString(),
                ex.getReason() != null ? ex.getReason() : ex.getMessage(),
                request.getRequestURI()
        );
        return ResponseEntity.status(ex.getStatusCode()).body(body);
    }

    // ---- Phase 4: Authentication Module exceptions ----

    @ExceptionHandler(InvalidOtpException.class)
    public ResponseEntity<ErrorResponse> handleInvalidOtp(InvalidOtpException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "INVALID_OTP", ex.getMessage(), request);
    }

    @ExceptionHandler(AccountLockedException.class)
    public ResponseEntity<ErrorResponse> handleAccountLocked(AccountLockedException ex, HttpServletRequest request) {
        return build(HttpStatus.LOCKED, "ACCOUNT_LOCKED", ex.getMessage(), request);
    }

    @ExceptionHandler(AccountSuspendedException.class)
    public ResponseEntity<ErrorResponse> handleAccountSuspended(AccountSuspendedException ex, HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, "ACCOUNT_SUSPENDED", ex.getMessage(), request);
    }

    @ExceptionHandler(DuplicateAccountException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateAccount(DuplicateAccountException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "CONFLICT", ex.getMessage(), request);
    }

    @ExceptionHandler(InvalidRefreshTokenException.class)
    public ResponseEntity<ErrorResponse> handleInvalidRefreshToken(InvalidRefreshTokenException ex, HttpServletRequest request) {
        return build(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", ex.getMessage(), request);
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleBadCredentials(BadCredentialsException ex, HttpServletRequest request) {
        return build(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Invalid credentials", request);
    }

    // ---- Phase 6: Complaint Module exceptions ----

    @ExceptionHandler(InvalidStateTransitionException.class)
    public ResponseEntity<ErrorResponse> handleInvalidStateTransition(InvalidStateTransitionException ex,
                                                                        HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "INVALID_TRANSITION", ex.getMessage(), request);
    }

    @ExceptionHandler(ComplaintLimitExceededException.class)
    public ResponseEntity<ErrorResponse> handleComplaintLimitExceeded(ComplaintLimitExceededException ex,
                                                                        HttpServletRequest request) {
        return build(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS", ex.getMessage(), request);
    }

    @ExceptionHandler(UnverifiedIdentifierException.class)
    public ResponseEntity<ErrorResponse> handleUnverifiedIdentifier(UnverifiedIdentifierException ex,
                                                                       HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, "FORBIDDEN", ex.getMessage(), request);
    }

    @ExceptionHandler(GracePeriodExpiredException.class)
    public ResponseEntity<ErrorResponse> handleGracePeriodExpired(GracePeriodExpiredException ex,
                                                                     HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "CONFLICT", ex.getMessage(), request);
    }

    // ---- Phase 11: Department Assignment Module exceptions ----

    @ExceptionHandler(BudgetApprovalRequiredException.class)
    public ResponseEntity<ErrorResponse> handleBudgetApprovalRequired(
            BudgetApprovalRequiredException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "BUDGET_APPROVAL_REQUIRED", ex.getMessage(), request);
    }

    @ExceptionHandler(StorageException.class)
    public ResponseEntity<ErrorResponse> handleStorage(StorageException ex,
                                                          HttpServletRequest request) {
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "STORAGE_ERROR",
                "Failed to store the uploaded file", request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrity(DataIntegrityViolationException ex, HttpServletRequest request) {
        // Catches races where a unique-constraint violation (mobile/email) slips
        // past the pre-check in AuthService under concurrent registration.
        return build(HttpStatus.CONFLICT, "CONFLICT", "The resource conflicts with an existing record", request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex,
                                                                 HttpServletRequest request) {
        ErrorResponse body = ErrorResponse.of(
                HttpStatus.BAD_REQUEST.value(),
                "BAD_REQUEST",
                ex.getMessage(),
                request.getRequestURI()
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex, HttpServletRequest request) {
        // Gap-backlog strict recheck (Sep 2026): this handler used to return
        // the 500 without logging anything, so the stack trace of every
        // unexpected server error was lost. The stable "UNHANDLED_EXCEPTION"
        // marker is also what the CloudWatch 5xx metric filter counts
        // (deployment/aws/terraform/app_monitoring.tf).
        log.error("UNHANDLED_EXCEPTION {} {}: {}", request.getMethod(), request.getRequestURI(), ex.toString(), ex);
        ErrorResponse body = ErrorResponse.of(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "INTERNAL_SERVER_ERROR",
                "An unexpected error occurred",
                request.getRequestURI()
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String error, String message,
                                                 HttpServletRequest request) {
        return ResponseEntity.status(status)
                .body(ErrorResponse.of(status.value(), error, message, request.getRequestURI()));
    }
}
