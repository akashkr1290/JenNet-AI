package com.jannetai.backend.exception;

/**
 * Registration OTP fix: the OTP could not be delivered, either because SMS is
 * not configured or because the SMS provider did not accept the message.
 * GlobalExceptionHandler maps it to HTTP 503 OTP_DELIVERY_FAILED with a safe
 * message, never provider details.
 *
 * For registration it propagates out of AuthService.register's transaction and
 * rolls back the new account and its undelivered OTP, so the citizen can simply
 * register again. OtpService.issueAndSend is declared noRollbackFor this
 * exception, so the password-reset paths can catch it without the surrounding
 * transaction being marked rollback-only (they must not reveal whether an
 * account exists).
 */
public class OtpDeliveryException extends RuntimeException {

    public OtpDeliveryException(String message) {
        super(message);
    }

    public OtpDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
