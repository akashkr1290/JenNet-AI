package com.jannetai.backend.config.logging;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Audit GAP-041 (SRS 26: "logged with a correlation ID"; SRS 27: logs include a
 * "correlation/request ID"). The request id is kept in the SLF4J MDC under
 * {@link #MDC_KEY}, returned to clients as {@link #HEADER} and forwarded to
 * ai-service in the same header. Pure JDK.
 */
public final class RequestIds {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    /** Accepted incoming ids: nginx's $request_id (32 hex chars) and UUIDs fit; anything else is replaced. */
    private static final Pattern ACCEPTED = Pattern.compile("[A-Za-z0-9._-]{8,64}");

    private RequestIds() {
    }

    /** The caller's id when it is well-formed (no log injection), otherwise a new one. */
    public static String adoptOrCreate(String incoming) {
        if (incoming != null && ACCEPTED.matcher(incoming).matches()) {
            return incoming;
        }
        return newId();
    }

    public static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
