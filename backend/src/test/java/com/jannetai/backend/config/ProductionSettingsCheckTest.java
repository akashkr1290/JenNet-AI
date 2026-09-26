package com.jannetai.backend.config;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Audit GAP-018: production fail-fast rules. Also run in the Phase 07 pure-JDK harness. NOT EXECUTED here via Maven. */
class ProductionSettingsCheckTest {

    private static Map<String, String> good() {
        Map<String, String> p = new HashMap<>();
        p.put("app.storage.provider", "s3");
        p.put("app.storage.s3.bucket", "jannet-ai-pilot-media-123");
        p.put("app.cors.allowed-origins", "https://jannet.example.org");
        p.put("app.ai-service.enabled", "true");
        p.put("app.ai-service.api-key", "a-real-shared-secret-value");
        p.put("app.notification.email.enabled", "false");
        p.put("app.notification.sms.enabled", "true");
        p.put("app.notification.sms.provider-url", "https://sms.example.test/send");
        p.put("app.geo.municipal-min-latitude", "12.8");
        return p;
    }

    @Test
    void aCompleteProductionConfigurationPasses() {
        var result = ProductionSettingsCheck.check(good()::get);
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void developmentDefaultsAreRejected() {
        Map<String, String> p = good();
        p.put("app.cors.allowed-origins", "http://localhost:3000,http://127.0.0.1:3000");
        p.put("app.ai-service.api-key", "change-me-in-every-real-environment");
        p.put("app.storage.provider", "local");
        p.put("app.storage.local-signing-secret", "local-dev-image-signing-secret-change-me");
        var errors = ProductionSettingsCheck.check(p::get).errors();
        assertThat(errors).anyMatch(e -> e.startsWith("CORS_ALLOWED_ORIGINS"));
        assertThat(errors).anyMatch(e -> e.startsWith("AI_SERVICE_API_KEY"));
        assertThat(errors).anyMatch(e -> e.startsWith("LOCAL_STORAGE_SIGNING_SECRET"));
    }

    @Test
    void enabledChannelsMustBeComplete() {
        Map<String, String> p = good();
        p.put("app.storage.s3.bucket", "");
        p.put("app.notification.email.enabled", "true");
        p.put("spring.mail.host", "localhost");
        p.put("app.notification.email.from-address", "no-reply@jannetai.local");
        p.put("app.notification.sms.provider-url", "");
        p.put("app.notification.push.enabled", "true");
        p.put("app.notification.push.credentials-path", "/nonexistent/firebase.json");
        var errors = ProductionSettingsCheck.check(p::get).errors();
        assertThat(errors).hasSize(5);
    }

    @Test
    void placeholdersThatStillWorkAreWarnings() {
        Map<String, String> p = good();
        p.put("app.notification.sms.enabled", "false");
        p.put("app.geo.municipal-min-latitude", "6.5");
        p.put("app.geo.municipal-max-latitude", "37.1");
        p.put("app.geo.municipal-min-longitude", "68.0");
        p.put("app.geo.municipal-max-longitude", "97.5");
        var result = ProductionSettingsCheck.check(p::get);
        assertThat(result.errors()).isEmpty();
        assertThat(result.warnings()).hasSize(2);
    }
}
