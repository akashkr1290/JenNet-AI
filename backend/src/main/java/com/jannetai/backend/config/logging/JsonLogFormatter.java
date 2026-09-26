package com.jannetai.backend.config.logging;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.StringJoiner;

/**
 * Audit GAP-041 (SRS 27: "Application logs are emitted in structured JSON
 * format, including timestamp, service name, log level, correlation/request
 * ID, and contextual metadata"): one JSON object per line. Pure JDK so the
 * output can be tested without logback; {@link JsonLogLayout} feeds it.
 */
public final class JsonLogFormatter {

    private JsonLogFormatter() {
    }

    public static String format(Instant timestamp, String level, String service, String logger, String thread,
                                String requestId, String message, String stackTrace) {
        StringJoiner json = new StringJoiner(",", "{", "}");
        json.add(field("timestamp", DateTimeFormatter.ISO_INSTANT.format(timestamp)));
        json.add(field("level", level));
        json.add(field("service", service));
        json.add(field("logger", logger));
        json.add(field("thread", thread));
        if (requestId != null && !requestId.isEmpty()) {
            json.add(field("requestId", requestId));
        }
        json.add(field("message", message));
        if (stackTrace != null && !stackTrace.isEmpty()) {
            json.add(field("stackTrace", stackTrace));
        }
        return json + "\n";
    }

    private static String field(String name, String value) {
        return quote(name) + ":" + quote(value);
    }

    /** RFC 8259 string encoding. */
    static String quote(String value) {
        String v = value == null ? "" : value;
        StringBuilder out = new StringBuilder(v.length() + 2).append('"');
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20 || c == ' ' || c == ' ') {
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
