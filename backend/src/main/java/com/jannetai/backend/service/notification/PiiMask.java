package com.jannetai.backend.service.notification;

/**
 * Remaining-gaps item 15 (privacy - sensitive logs): contact identifiers are
 * masked before they reach application logs or exception messages (which
 * NotificationService logs on every retry and which ship to CloudWatch in
 * production). Enough is kept to correlate a log line with a known user
 * during support ("c***@example.com", "********11"), never the full value.
 */
public final class PiiMask {

    private PiiMask() {
    }

    public static String email(String address) {
        if (address == null || address.isBlank()) {
            return "(none)";
        }
        int at = address.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        return address.charAt(0) + "***" + address.substring(at);
    }

    public static String phone(String number) {
        if (number == null || number.isBlank()) {
            return "(none)";
        }
        String digits = number.replaceAll("[^0-9]", "");
        if (digits.length() <= 2) {
            return "***";
        }
        return "*".repeat(digits.length() - 2) + digits.substring(digits.length() - 2);
    }
}
