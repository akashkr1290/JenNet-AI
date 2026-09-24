package com.jannetai.backend.client.ai;

/**
 * Thrown by {@link AiServiceClient} for every failure mode - HTTP error
 * envelope from ai-service (SRS 20.6: {@code {error_code, message, details}},
 * see {@code ai-service/app/core/exceptions.py}), connect/read timeout, or
 * an unparseable response.
 *
 * Deliberately unchecked and always caught inside
 * {@code AiClassificationService} - see that class's Javadoc for why an
 * ai-service failure must never propagate out to
 * {@link com.jannetai.backend.controller.ComplaintController} or
 * {@link com.jannetai.backend.exception.GlobalExceptionHandler}: it is not
 * a citizen-facing error, complaint submission must keep succeeding even
 * when ai-service is down.
 */
public class AiServiceCallException extends RuntimeException {

    private final String errorCode;

    public AiServiceCallException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public AiServiceCallException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
