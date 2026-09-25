package com.jannetai.backend.service;

import java.math.BigDecimal;
import java.util.StringJoiner;

/**
 * Audit GAP-021: builds the {@code audit_logs.details} JSON object with full
 * RFC 8259 escaping. audit_logs.details is a MySQL JSON column; the previous
 * string concatenation (only {@code "} was replaced) produced invalid JSON for
 * any note containing a newline, tab, backslash or other control character -
 * MySQL rejected the insert (error 3140) and the WHOLE staff action (the
 * verification decision, the status update, the note itself) was rolled back.
 *
 * <p>Pure JDK so it can be tested without Spring; values may be String,
 * Number, Boolean, Enum or null (anything else is written via toString()).
 *
 * <pre>AuditJson.of("reason", reason, "sla_hours", 72)</pre>
 */
public final class AuditJson {

    private AuditJson() {
    }

    /** @param keyValues alternating key (String) and value pairs */
    public static String of(Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("AuditJson.of needs key/value pairs");
        }
        StringJoiner json = new StringJoiner(",", "{", "}");
        for (int i = 0; i < keyValues.length; i += 2) {
            json.add(quote(String.valueOf(keyValues[i])) + ":" + value(keyValues[i + 1]));
        }
        return json.toString();
    }

    private static String value(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof Boolean) {
            return v.toString();
        }
        if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) {
            return v.toString();
        }
        if (v instanceof BigDecimal bd) {
            return bd.toPlainString();
        }
        if ((v instanceof Double d && Double.isFinite(d)) || (v instanceof Float f && Float.isFinite(f))) {
            return v.toString();
        }
        if (v instanceof Enum<?> e) {
            return quote(e.name());
        }
        return quote(v.toString());
    }

    /** RFC 8259 string: quotes, backslash and every control character escaped. */
    public static String quote(String s) {
        StringBuilder out = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
