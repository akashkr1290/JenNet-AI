package com.jannetai.backend.service.complaint;

import java.util.Set;

/**
 * Audit GAP-010: which AI failures are worth retrying, and when. Pure JDK so it
 * can be tested without Spring.
 *
 * Retryable = the next attempt can plausibly succeed without anyone changing
 * anything: ai-service down or restarting, a model timeout, a 5xx, a garbled
 * response, or a transient image-fetch failure. Everything else (the image
 * itself is unusable, the request is invalid, the ai-service key is wrong)
 * goes straight to the Verification Team - retrying would only delay the
 * citizen's complaint.
 */
public final class AiRetryPolicy {

    private static final Set<String> NON_RETRYABLE = Set.of(
            "UNPROCESSABLE_IMAGE",          // ai-service 422: blurry / too dark / too small
            "INVALID_REQUEST",              // ai-service 400
            "UNAUTHORIZED",                 // ai-service 401: AI_SERVICE_API_KEY mismatch (configuration)
            "NO_IMAGE_ON_FILE",             // backend: the complaint has no stored photo
            "REQUEST_SERIALIZATION_FAILED"  // backend bug, deterministic
    );

    private AiRetryPolicy() {
    }

    public static boolean isRetryable(String errorCode) {
        return errorCode == null || !NON_RETRYABLE.contains(errorCode);
    }

    /**
     * @param attemptsMade attempts already made (>= 1)
     * @return true when another attempt is allowed
     */
    public static boolean shouldRetry(String errorCode, int attemptsMade, int maxAttempts) {
        return isRetryable(errorCode) && attemptsMade < maxAttempts;
    }

    /** Exponential backoff: base, 2x base, 4x base ... capped at 1 hour. */
    public static long backoffSeconds(int attemptsMade, long baseSeconds) {
        int exponent = Math.max(0, Math.min(attemptsMade - 1, 16));
        long delay = baseSeconds * (1L << exponent);
        return Math.min(delay, 3600L);
    }
}
