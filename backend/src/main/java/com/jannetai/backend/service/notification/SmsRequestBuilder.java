package com.jannetai.backend.service.notification;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Audit GAP-003: builds the outbound HTTP request for an SMS provider from
 * configuration only, so a real provider can be connected by setting
 * environment variables instead of writing code. Pure JDK (no Spring types),
 * so the request shape can be unit-tested in isolation.
 *
 * Supported variations (all configuration-driven, see NotificationProperties.Sms):
 * <ul>
 *   <li>body: JSON object or application/x-www-form-urlencoded;</li>
 *   <li>authentication: {@code Authorization: Bearer <key>}, a custom header
 *       carrying the key, HTTP Basic ({@code username:key}), the key as a query
 *       parameter, or none (for example an IP-allow-listed relay);</li>
 *   <li>field names for the number and the text, an optional sender ID field,
 *       and the Indian DLT entity-ID / template-ID fields;</li>
 *   <li>number format: {@code +919876543210}, {@code 919876543210} or the
 *       national {@code 9876543210};</li>
 *   <li>additional fixed parameters (for example a route or a unicode flag).</li>
 * </ul>
 * No provider is named or assumed here - the operator maps these settings to
 * the API of the provider they have a contract with.
 */
public final class SmsRequestBuilder {

    public enum RequestFormat { JSON, FORM }

    public enum AuthScheme { BEARER, HEADER, BASIC, QUERY, NONE }

    public enum NumberFormat { E164, DIGITS, NATIONAL }

    /** Everything needed to build one request. Blank field names mean "do not send that field". */
    public record Settings(
            String url,
            RequestFormat format,
            AuthScheme authScheme,
            String apiKey,
            String authHeaderName,
            String authQueryParam,
            String basicUsername,
            NumberFormat numberFormat,
            String defaultCountryCode,
            String toField,
            String messageField,
            String senderIdField,
            String senderId,
            String dltEntityIdField,
            String dltEntityId,
            String dltTemplateIdField,
            String dltTemplateId,
            Map<String, String> extraParams) {
    }

    /** The request to execute. {@code headers} never contains Content-Type (see {@link #contentType()}). */
    public record Request(String url, String contentType, Map<String, String> headers, String body) {
    }

    private SmsRequestBuilder() {
    }

    public static RequestFormat parseFormat(String value) {
        return parse(RequestFormat.class, value, RequestFormat.JSON, "SMS_PROVIDER_REQUEST_FORMAT");
    }

    public static AuthScheme parseAuthScheme(String value) {
        return parse(AuthScheme.class, value, AuthScheme.BEARER, "SMS_PROVIDER_AUTH_SCHEME");
    }

    public static NumberFormat parseNumberFormat(String value) {
        return parse(NumberFormat.class, value, NumberFormat.E164, "SMS_PROVIDER_NUMBER_FORMAT");
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, E fallback, String variable) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(variable + " has an unsupported value: " + value.trim());
        }
    }

    /**
     * @param e164Number the recipient as {@code +<country code><number>} (from SmsNumberFormatter)
     */
    public static Request build(Settings s, String e164Number, String message) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put(s.toField(), formatNumber(e164Number, s.numberFormat(), s.defaultCountryCode()));
        params.put(s.messageField(), message);
        putIfConfigured(params, s.senderIdField(), s.senderId());
        putIfConfigured(params, s.dltEntityIdField(), s.dltEntityId());
        putIfConfigured(params, s.dltTemplateIdField(), s.dltTemplateId());
        if (s.extraParams() != null) {
            s.extraParams().forEach(params::putIfAbsent); // never overrides the fields above
        }

        Map<String, String> headers = new LinkedHashMap<>();
        String url = s.url();
        switch (s.authScheme()) {
            case BEARER -> headers.put("Authorization", "Bearer " + s.apiKey());
            case HEADER -> headers.put(blankTo(s.authHeaderName(), "Authorization"), s.apiKey());
            case BASIC -> headers.put("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                    (s.basicUsername() + ":" + s.apiKey()).getBytes(StandardCharsets.UTF_8)));
            case QUERY -> url = url + (url.contains("?") ? "&" : "?")
                    + encode(blankTo(s.authQueryParam(), "apikey")) + "=" + encode(s.apiKey());
            case NONE -> { /* e.g. an allow-listed relay - no credential sent */ }
        }

        if (s.format() == RequestFormat.FORM) {
            StringJoiner form = new StringJoiner("&");
            params.forEach((k, v) -> form.add(encode(k) + "=" + encode(v)));
            return new Request(url, "application/x-www-form-urlencoded;charset=UTF-8",
                    Collections.unmodifiableMap(headers), form.toString());
        }
        StringJoiner json = new StringJoiner(",", "{", "}");
        params.forEach((k, v) -> json.add(jsonString(k) + ":" + jsonString(v)));
        return new Request(url, "application/json", Collections.unmodifiableMap(headers), json.toString());
    }

    /**
     * Names of missing settings for this auth scheme (never values), comma separated;
     * empty when the provider settings are complete.
     */
    public static String missingCredentials(AuthScheme scheme, String apiKey, String basicUsername) {
        StringJoiner missing = new StringJoiner(", ");
        if (scheme != AuthScheme.NONE && (apiKey == null || apiKey.isBlank())) {
            missing.add("SMS_PROVIDER_API_KEY empty");
        }
        if (scheme == AuthScheme.BASIC && (basicUsername == null || basicUsername.isBlank())) {
            missing.add("SMS_PROVIDER_BASIC_USERNAME empty");
        }
        return missing.toString();
    }

    static String formatNumber(String e164Number, NumberFormat format, String defaultCountryCode) {
        String digits = e164Number.startsWith("+") ? e164Number.substring(1) : e164Number;
        return switch (format) {
            case E164 -> "+" + digits;
            case DIGITS -> digits;
            case NATIONAL -> {
                String cc = defaultCountryCode == null ? "" : defaultCountryCode.replace("+", "").trim();
                yield !cc.isEmpty() && digits.startsWith(cc) ? digits.substring(cc.length()) : digits;
            }
        };
    }

    private static void putIfConfigured(Map<String, String> params, String field, String value) {
        if (field != null && !field.isBlank() && value != null && !value.isBlank()) {
            params.put(field.trim(), value.trim());
        }
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    /** Minimal RFC 8259 string encoding (quotes, backslash, control characters). */
    static String jsonString(String value) {
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
                    if (c < 0x20) {
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
