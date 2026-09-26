package com.jannetai.backend.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Audit GAP-018 ("add a startup check that fails fast on missing production
 * settings"): the rules, over a property lookup so they can be unit-tested
 * without Spring. {@link ProductionConfigurationValidator} runs them under the
 * {@code prod} profile and refuses to start when {@link #errors} is not empty.
 *
 * Errors are settings a production instance cannot run correctly or safely
 * without. Warnings are operator data that has a working (but placeholder)
 * default. Messages name the environment variable, never its value.
 */
public final class ProductionSettingsCheck {

    public record Result(List<String> errors, List<String> warnings) {
    }

    static final String PUBLIC_AI_KEY_PLACEHOLDER = "change-me-in-every-real-environment";
    static final String DEFAULT_SIGNING_SECRET = "local-dev-image-signing-secret-change-me";
    // The all-India placeholder box from application.yml (GEO_*).
    static final String[] PLACEHOLDER_GEO = {"6.5", "37.1", "68.0", "97.5"};

    private ProductionSettingsCheck() {
    }

    /** @param property resolved Spring property by name; null or "" when unset */
    public static Result check(Function<String, String> property) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // --- storage (GAP-018: Terraform creates an S3 bucket that was never used)
        String provider = lower(property.apply("app.storage.provider"));
        if ("s3".equals(provider)) {
            if (blank(property.apply("app.storage.s3.bucket"))) {
                errors.add("STORAGE_S3_BUCKET is empty while STORAGE_PROVIDER=s3");
            }
        } else if ("local".equals(provider) || provider.isEmpty()) {
            String secret = property.apply("app.storage.local-signing-secret");
            if (blank(secret) || DEFAULT_SIGNING_SECRET.equals(secret) || secret.length() < 32) {
                errors.add("LOCAL_STORAGE_SIGNING_SECRET must be set to a random value of at least 32 characters "
                        + "when STORAGE_PROVIDER=local (the public default signs image links)");
            }
        } else {
            errors.add("STORAGE_PROVIDER must be 'local' or 's3'");
        }

        // --- CORS: the web app's real HTTPS origin, never the localhost default
        String origins = property.apply("app.cors.allowed-origins");
        if (blank(origins)) {
            errors.add("CORS_ALLOWED_ORIGINS is empty - set the web app origin, e.g. https://jannet.example.org");
        } else {
            for (String raw : origins.split(",")) {
                String origin = raw.trim().toLowerCase(Locale.ROOT);
                if (origin.isEmpty()) {
                    continue;
                }
                if (origin.contains("localhost") || origin.contains("127.0.0.1") || origin.equals("*")) {
                    errors.add("CORS_ALLOWED_ORIGINS contains a development origin (" + raw.trim() + ")");
                } else if (!origin.startsWith("https://")) {
                    warnings.add("CORS_ALLOWED_ORIGINS contains a non-HTTPS origin (" + raw.trim()
                            + ") - SRS 27.2 requires TLS");
                }
            }
        }

        // --- backend <-> ai-service shared secret
        if ("true".equalsIgnoreCase(property.apply("app.ai-service.enabled"))) {
            String key = property.apply("app.ai-service.api-key");
            if (blank(key) || PUBLIC_AI_KEY_PLACEHOLDER.equals(key)) {
                errors.add("AI_SERVICE_API_KEY is empty or the public placeholder");
            }
        }

        // --- notification channels: switched on means fully configured
        if ("true".equalsIgnoreCase(property.apply("app.notification.email.enabled"))) {
            String host = property.apply("spring.mail.host");
            if (blank(host) || "localhost".equalsIgnoreCase(host.trim())) {
                errors.add("SMTP_HOST must be the real SMTP relay when NOTIFICATION_EMAIL_ENABLED=true");
            }
            String from = property.apply("app.notification.email.from-address");
            if (blank(from) || from.trim().toLowerCase(Locale.ROOT).endsWith(".local")) {
                errors.add("NOTIFICATION_EMAIL_FROM must be a verified sender address when NOTIFICATION_EMAIL_ENABLED=true");
            }
        }
        if ("true".equalsIgnoreCase(property.apply("app.notification.sms.enabled"))
                && blank(property.apply("app.notification.sms.provider-url"))) {
            errors.add("SMS_PROVIDER_URL is empty while NOTIFICATION_SMS_ENABLED=true");
        }
        if ("true".equalsIgnoreCase(property.apply("app.notification.push.enabled"))) {
            String path = property.apply("app.notification.push.credentials-path");
            if (blank(path) || !Files.isReadable(Path.of(path.trim()))) {
                errors.add("FIREBASE_CREDENTIALS_PATH must point to a readable service-account file when NOTIFICATION_PUSH_ENABLED=true");
            }
        }
        if (!"true".equalsIgnoreCase(property.apply("app.notification.sms.enabled"))
                && !"true".equalsIgnoreCase(property.apply("app.notification.email.enabled"))) {
            warnings.add("Neither SMS nor e-mail notifications are enabled: registration OTPs cannot be delivered "
                    + "(HTTP 503) - see docs/SMS_PROVIDER_CONFIGURATION.md");
        }

        // --- jurisdiction: operator data with a placeholder default
        if (PLACEHOLDER_GEO[0].equals(trim(property.apply("app.geo.municipal-min-latitude")))
                && PLACEHOLDER_GEO[1].equals(trim(property.apply("app.geo.municipal-max-latitude")))
                && PLACEHOLDER_GEO[2].equals(trim(property.apply("app.geo.municipal-min-longitude")))
                && PLACEHOLDER_GEO[3].equals(trim(property.apply("app.geo.municipal-max-longitude")))) {
            warnings.add("GEO_MIN_LAT/GEO_MAX_LAT/GEO_MIN_LNG/GEO_MAX_LNG still cover all of India - set the municipal boundary");
        }
        return new Result(List.copyOf(errors), List.copyOf(warnings));
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String lower(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
