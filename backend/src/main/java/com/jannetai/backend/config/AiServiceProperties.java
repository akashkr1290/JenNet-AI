package com.jannetai.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound to {@code app.ai-service.*} in application.yml (Phase 8:
 * backend&lt;-&gt;ai-service integration). Mirrors {@link JwtProperties}'s
 * binding style.
 *
 * {@code apiKey} must match {@code ai-service}'s own {@code
 * AI_SERVICE_API_KEY} (its {@code app/config.py: Settings.ai_service_api_key})
 * exactly - it is the shared secret sent as the {@code X-Internal-Api-Key}
 * header on every call (SRS 20.3). Both sides default to the same
 * placeholder string ({@code change-me-in-every-real-environment}) so local
 * development works with zero configuration; see root {@code .env.example}.
 */
@ConfigurationProperties(prefix = "app.ai-service")
public class AiServiceProperties {

    /** Root URL of the ai-service FastAPI process, e.g. {@code http://localhost:8001}. */
    private String baseUrl = "http://localhost:8001";

    private String apiKey = "change-me-in-every-real-environment";

    /**
     * Master switch. When {@code false}, {@code AiClassificationService}
     * never calls out to ai-service at all and every complaint is simply
     * left parked at AI_PROCESSING for the Phase 6 manual override - useful
     * for environments (including this one) where ai-service isn't
     * actually running. Defaults to {@code true}; failures at call time are
     * still handled gracefully either way (see AiClassificationService).
     */
    private boolean enabled = true;

    private int connectTimeoutMs = 5000;
    private int readTimeoutMs = 15000;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }
}
