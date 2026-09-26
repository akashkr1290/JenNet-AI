package com.jannetai.backend.config;

import java.util.Set;

/**
 * Audit GAP-041 (SRS 26: "Transient failures (e.g., temporary database
 * connection loss) are retried automatically up to 3 times with exponential
 * backoff before surfacing an error to the caller"). Which failures count as
 * transient, and the backoff. Pure JDK.
 */
public final class TransientFailures {

    /** Matched by class name so this class needs no Hibernate / MySQL driver types. */
    private static final Set<String> TRANSIENT_CLASS_NAMES = Set.of(
            "org.hibernate.exception.JDBCConnectionException",
            "com.mysql.cj.jdbc.exceptions.CommunicationsException",
            "com.mysql.cj.exceptions.CJCommunicationsException");

    static final long MAX_DELAY_MS = 10_000;

    private TransientFailures() {
    }

    /**
     * True when the cause chain shows a lost or unavailable database
     * connection (as opposed to a constraint violation, a bad query or a
     * deadlock inside a running transaction, which are never retried here).
     */
    public static boolean isTransient(Throwable failure) {
        int depth = 0;
        for (Throwable t = failure; t != null && depth < 20; t = t.getCause(), depth++) {
            if (t instanceof java.sql.SQLTransientConnectionException
                    || t instanceof java.sql.SQLRecoverableException
                    || t instanceof java.net.ConnectException
                    || TRANSIENT_CLASS_NAMES.contains(t.getClass().getName())) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    /** Delay before retry number {@code attempt} (1-based): base, 2x base, 4x base ..., capped. */
    public static long backoffMillis(long baseDelayMs, int attempt) {
        long base = Math.max(0, baseDelayMs);
        int shift = Math.max(0, Math.min(attempt - 1, 20));
        return Math.min(MAX_DELAY_MS, base << shift);
    }
}
