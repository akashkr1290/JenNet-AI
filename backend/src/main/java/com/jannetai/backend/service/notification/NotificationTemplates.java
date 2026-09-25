package com.jannetai.backend.service.notification;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * Audit GAP-024 (SRS 15.13 / 20: notifications are "templated, localized
 * (English and Hindi at minimum)"). One template per event and language,
 * with {@code {placeholder}} substitution. The recipient's language is their
 * {@code personal_language} setting (EN or HI, PersonalSettingKey.LANGUAGE);
 * anything else falls back to English.
 *
 * The English texts of the three original events are unchanged. Pure JDK so
 * the templates can be tested without Spring.
 *
 * SMS in India: every text sent by SMS must match a DLT-registered template.
 * These texts (both languages) are what the operator registers; see
 * docs/SMS_PROVIDER_CONFIGURATION.md.
 */
public final class NotificationTemplates {

    public enum Event {
        STATUS_CHANGED,
        OFFICER_ASSIGNED,
        SLA_WARNING,
        COMPLAINT_ESCALATED,
        DUPLICATE_REVIEW_REQUIRED,
        NO_OFFICER_AVAILABLE,
        APPEAL_APPROVED,
        APPEAL_DENIED
    }

    public static final String ENGLISH = "EN";
    public static final String HINDI = "HI";

    private static final Map<Event, String> EN = new EnumMap<>(Event.class);
    private static final Map<Event, String> HI = new EnumMap<>(Event.class);
    private static final Map<String, String> STATUS_EN = Map.ofEntries(
            Map.entry("SUBMITTED", "submitted"),
            Map.entry("VERIFIED", "verified"),
            Map.entry("ASSIGNED", "assigned to an officer"),
            Map.entry("IN_PROGRESS", "in progress"),
            Map.entry("RESOLVED", "resolved"),
            Map.entry("REJECTED", "rejected"),
            Map.entry("CLOSED", "closed"),
            Map.entry("DUPLICATE", "marked as a duplicate of an existing complaint"),
            Map.entry("ESCALATED", "escalated"),
            Map.entry("REOPENED", "reopened"));
    private static final Map<String, String> STATUS_HI = Map.ofEntries(
            Map.entry("SUBMITTED", "दर्ज"),
            Map.entry("VERIFIED", "सत्यापित"),
            Map.entry("ASSIGNED", "अधिकारी को सौंपी गई"),
            Map.entry("IN_PROGRESS", "कार्य प्रगति पर"),
            Map.entry("RESOLVED", "समाधान हो गया"),
            Map.entry("REJECTED", "अस्वीकृत"),
            Map.entry("CLOSED", "बंद"),
            Map.entry("DUPLICATE", "पहले से दर्ज शिकायत की डुप्लिकेट"),
            Map.entry("ESCALATED", "उच्च स्तर पर भेजी गई"),
            Map.entry("REOPENED", "फिर से खोली गई"));

    static {
        EN.put(Event.STATUS_CHANGED, "Your complaint {ref} is now {status}.");
        EN.put(Event.OFFICER_ASSIGNED, "New complaint assigned to you: {ref} ({category}, {severity} severity).");
        EN.put(Event.SLA_WARNING, "SLA warning: complaint {ref} is approaching its SLA deadline. Please review it soon.");
        EN.put(Event.COMPLAINT_ESCALATED,
                "Escalation: complaint {ref} has exceeded its {hours}-hour SLA and has been escalated. Please take action.");
        EN.put(Event.DUPLICATE_REVIEW_REQUIRED,
                "Complaint {ref} may be a duplicate of complaint {parentRef}. Please review it in the verification queue.");
        EN.put(Event.NO_OFFICER_AVAILABLE,
                "Complaint {ref} was routed to {department}, but no officer is available. Please assign an officer.");
        EN.put(Event.APPEAL_APPROVED,
                "Your appeal for complaint {ref} was approved. The complaint will be reviewed again.");
        EN.put(Event.APPEAL_DENIED, "Your appeal for complaint {ref} was not approved.{note}");

        HI.put(Event.STATUS_CHANGED, "आपकी शिकायत {ref} की स्थिति: {status}।");
        HI.put(Event.OFFICER_ASSIGNED, "आपको नई शिकायत सौंपी गई है: {ref} ({category}, गंभीरता: {severity})।");
        HI.put(Event.SLA_WARNING, "SLA चेतावनी: शिकायत {ref} की समय-सीमा निकट है। कृपया इसे शीघ्र देखें।");
        HI.put(Event.COMPLAINT_ESCALATED,
                "एस्केलेशन: शिकायत {ref} ने {hours} घंटे की SLA सीमा पार कर ली है और इसे उच्च स्तर पर भेजा गया है। कृपया कार्रवाई करें।");
        HI.put(Event.DUPLICATE_REVIEW_REQUIRED,
                "शिकायत {ref} शिकायत {parentRef} की डुप्लिकेट हो सकती है। कृपया सत्यापन कतार में इसकी समीक्षा करें।");
        HI.put(Event.NO_OFFICER_AVAILABLE,
                "शिकायत {ref} {department} को भेजी गई है, लेकिन कोई अधिकारी उपलब्ध नहीं है। कृपया अधिकारी नियुक्त करें।");
        HI.put(Event.APPEAL_APPROVED,
                "शिकायत {ref} पर आपकी अपील स्वीकार कर ली गई है। शिकायत की फिर से समीक्षा की जाएगी।");
        HI.put(Event.APPEAL_DENIED, "शिकायत {ref} पर आपकी अपील स्वीकार नहीं की गई।{note}");
    }

    private NotificationTemplates() {
    }

    /** EN or HI; null/blank/unknown -> EN. */
    public static String normaliseLanguage(String language) {
        if (language != null && HINDI.equals(language.trim().toUpperCase(Locale.ROOT))) {
            return HINDI;
        }
        return ENGLISH;
    }

    /** Human-readable complaint status in the given language (falls back to the enum name). */
    public static String statusLabel(String statusName, String language) {
        Map<String, String> labels = HINDI.equals(normaliseLanguage(language)) ? STATUS_HI : STATUS_EN;
        return labels.getOrDefault(statusName, statusName);
    }

    /**
     * Renders {@code event} in {@code language}. Unknown placeholders are left as-is;
     * a null parameter value renders as an empty string.
     */
    public static String render(Event event, String language, Map<String, String> params) {
        String template = (HINDI.equals(normaliseLanguage(language)) ? HI : EN).get(event);
        String out = template;
        for (Map.Entry<String, String> p : params.entrySet()) {
            out = out.replace("{" + p.getKey() + "}", p.getValue() == null ? "" : p.getValue());
        }
        return out;
    }
}
