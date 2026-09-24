package com.jannetai.backend.client.ai;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.jannetai.backend.config.AiServiceProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.function.Supplier;

/**
 * Thin HTTP client for ai-service's {@code POST /api/v1/ai/classify} (SRS
 * 20.3, Phase 7 contract), {@code POST /api/v1/ai/duplicate-check} (SRS
 * 20.3/15.6, Phase 9), and, as of Phase 10, {@code POST
 * /api/v1/ai/priority-predict} (SRS 20.3/15.8) and {@code POST
 * /api/v1/ai/budget-predict} (SRS 20.3/15.9). Owns exactly two things per
 * endpoint: building the request the way ai-service expects it, and
 * translating every possible failure into a single
 * {@link AiServiceCallException} - all business decision-making
 * (auto-approve vs. manual review, duplicate merge vs. manual duplicate
 * review, severity/budget assignment, persisting a Prediction/Budget row,
 * moving the complaint's state) lives in {@code service/complaint}, not
 * here.
 *
 * Uses its own snake_case-configured {@link ObjectMapper}, deliberately
 * separate from the Spring MVC-managed one that serializes this backend's
 * own camelCase REST API to Flutter (PROJECT_INTEGRATION.md Section 2) -
 * the two API contracts are unrelated and must not influence each other.
 */
@Component
public class AiServiceClient {

    private static final Logger log = LoggerFactory.getLogger(AiServiceClient.class);
    private static final String CLASSIFY_PATH = "/api/v1/ai/classify";
    private static final String DUPLICATE_CHECK_PATH = "/api/v1/ai/duplicate-check";
    private static final String PRIORITY_PREDICT_PATH = "/api/v1/ai/priority-predict";
    private static final String BUDGET_PREDICT_PATH = "/api/v1/ai/budget-predict";
    private static final String API_KEY_HEADER = "X-Internal-Api-Key";

    private final RestTemplate restTemplate;
    private final AiServiceProperties properties;
    private final ObjectMapper aiServiceObjectMapper;
    private final MeterRegistry meterRegistry;

    public AiServiceClient(RestTemplate aiServiceRestTemplate, AiServiceProperties aiServiceProperties,
                            MeterRegistry meterRegistry) {
        this.restTemplate = aiServiceRestTemplate;
        this.properties = aiServiceProperties;
        this.meterRegistry = meterRegistry;
        this.aiServiceObjectMapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * Gap-backlog Patch 17 (Sep 2026 audit): every public call below is
     * wrapped in this, so "AI inference latency" and "AI failures"
     * (Patch 17's own wording) are real Micrometer metrics
     * (`ai_service_call_duration_seconds{endpoint=...}` timer,
     * `ai_service_calls_total{endpoint=...,outcome=success|failure}`
     * counter) exposed at {@code /actuator/metrics} /
     * {@code /actuator/prometheus} - not just log lines. Deliberately
     * wraps the whole method body (not just the restTemplate.exchange
     * call) so request-serialization and response-parsing failures count
     * as failures too, not just transport-level ones.
     */
    private <T> T timed(String endpoint, Supplier<T> call) {
        Timer.Sample sample = Timer.start(meterRegistry);
        boolean success = false;
        try {
            T result = call.get();
            success = true;
            return result;
        } finally {
            String outcome = success ? "success" : "failure";
            meterRegistry.counter("ai_service_calls_total", "endpoint", endpoint, "outcome", outcome).increment();
            sample.stop(meterRegistry.timer("ai_service_call_duration", "endpoint", endpoint));
        }
    }

    /**
     * @throws AiServiceCallException on any non-2xx response, connectivity
     *                                failure/timeout, or unparseable body.
     *                                Callers must not let this propagate to
     *                                the citizen-facing API - see the
     *                                class-level Javadoc.
     */
    public AiClassifyResult classify(AiClassifyRequest request) {
        return timed("classify", () -> {
            String url = properties.getBaseUrl() + CLASSIFY_PATH;
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set(API_KEY_HEADER, properties.getApiKey());

            String requestBody;
            try {
                requestBody = aiServiceObjectMapper.writeValueAsString(request);
            } catch (Exception e) {
                throw new AiServiceCallException("REQUEST_SERIALIZATION_FAILED",
                        "Failed to serialize the ai-service classify request.", e);
            }

            ResponseEntity<String> response;
            try {
                response = restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(requestBody, headers), String.class);
            } catch (HttpStatusCodeException e) {
                throw translateErrorEnvelope(e);
            } catch (ResourceAccessException e) {
                // Connection refused, DNS failure, or connect/read timeout exceeded
                // (RestTemplateConfig's configured connectTimeoutMs/readTimeoutMs).
                log.warn("ai-service unreachable at {}: {}", url, e.getMessage());
                throw new AiServiceCallException("AI_SERVICE_UNREACHABLE",
                        "Could not reach ai-service at " + url + ": " + e.getMessage(), e);
            }

            try {
                return aiServiceObjectMapper.readValue(response.getBody(), AiClassifyResult.class);
            } catch (Exception e) {
                throw new AiServiceCallException("RESPONSE_PARSE_FAILED",
                        "Failed to parse ai-service classify response.", e);
            }
        });
    }

    /**
     * Phase 9 (SRS 20.3, 15.6): {@code POST /api/v1/ai/duplicate-check}.
     * Same request-building/error-translation shape as {@link #classify},
     * against a different path and payload.
     *
     * @throws AiServiceCallException on any non-2xx response, connectivity
     *                                failure/timeout, or unparseable body.
     *                                Same never-let-this-propagate-to-the-
     *                                citizen contract as {@link #classify}.
     */
    public AiDuplicateCheckResult checkDuplicate(AiDuplicateCheckRequest request) {
        return timed("duplicate_check", () -> {
            String url = properties.getBaseUrl() + DUPLICATE_CHECK_PATH;
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set(API_KEY_HEADER, properties.getApiKey());

            String requestBody;
            try {
                requestBody = aiServiceObjectMapper.writeValueAsString(request);
            } catch (Exception e) {
                throw new AiServiceCallException("REQUEST_SERIALIZATION_FAILED",
                        "Failed to serialize the ai-service duplicate-check request.", e);
            }

            ResponseEntity<String> response;
            try {
                response = restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(requestBody, headers), String.class);
            } catch (HttpStatusCodeException e) {
                throw translateErrorEnvelope(e);
            } catch (ResourceAccessException e) {
                log.warn("ai-service unreachable at {}: {}", url, e.getMessage());
                throw new AiServiceCallException("AI_SERVICE_UNREACHABLE",
                        "Could not reach ai-service at " + url + ": " + e.getMessage(), e);
            }

            try {
                return aiServiceObjectMapper.readValue(response.getBody(), AiDuplicateCheckResult.class);
            } catch (Exception e) {
                throw new AiServiceCallException("RESPONSE_PARSE_FAILED",
                        "Failed to parse ai-service duplicate-check response.", e);
            }
        });
    }

    /**
     * Phase 10 (SRS 20.3 Table 24, 15.8): {@code POST
     * /api/v1/ai/priority-predict}. Same request-building/error-translation
     * shape as {@link #classify}/{@link #checkDuplicate}, against a
     * different path and payload.
     *
     * @throws AiServiceCallException on any non-2xx response, connectivity
     *                                failure/timeout, or unparseable body.
     *                                Same never-let-this-propagate-to-the-
     *                                citizen contract as {@link #classify}.
     */
    public AiPriorityPredictResult predictPriority(AiPriorityPredictRequest request) {
        return timed("priority_predict", () -> {
            String url = properties.getBaseUrl() + PRIORITY_PREDICT_PATH;
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set(API_KEY_HEADER, properties.getApiKey());

            String requestBody;
            try {
                requestBody = aiServiceObjectMapper.writeValueAsString(request);
            } catch (Exception e) {
                throw new AiServiceCallException("REQUEST_SERIALIZATION_FAILED",
                        "Failed to serialize the ai-service priority-predict request.", e);
            }

            ResponseEntity<String> response;
            try {
                response = restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(requestBody, headers), String.class);
            } catch (HttpStatusCodeException e) {
                throw translateErrorEnvelope(e);
            } catch (ResourceAccessException e) {
                log.warn("ai-service unreachable at {}: {}", url, e.getMessage());
                throw new AiServiceCallException("AI_SERVICE_UNREACHABLE",
                        "Could not reach ai-service at " + url + ": " + e.getMessage(), e);
            }

            try {
                return aiServiceObjectMapper.readValue(response.getBody(), AiPriorityPredictResult.class);
            } catch (Exception e) {
                throw new AiServiceCallException("RESPONSE_PARSE_FAILED",
                        "Failed to parse ai-service priority-predict response.", e);
            }
        });
    }

    /**
     * Phase 10 (SRS 20.3 Table 24, 15.9): {@code POST
     * /api/v1/ai/budget-predict}. Same request-building/error-translation
     * shape as {@link #classify}/{@link #checkDuplicate}, against a
     * different path and payload. SRS 15.9 Dependencies: "Priority
     * Prediction Module" - callers must call {@link #predictPriority}
     * first and pass its (possibly staff-overridden) severity in here; this
     * client makes no ordering guarantee itself, that's
     * {@code service.complaint.PriorityBudgetPredictionService}'s job.
     *
     * @throws AiServiceCallException on any non-2xx response, connectivity
     *                                failure/timeout, or unparseable body.
     *                                Same never-let-this-propagate-to-the-
     *                                citizen contract as {@link #classify}.
     */
    public AiBudgetPredictResult predictBudget(AiBudgetPredictRequest request) {
        return timed("budget_predict", () -> {
            String url = properties.getBaseUrl() + BUDGET_PREDICT_PATH;
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set(API_KEY_HEADER, properties.getApiKey());

            String requestBody;
            try {
                requestBody = aiServiceObjectMapper.writeValueAsString(request);
            } catch (Exception e) {
                throw new AiServiceCallException("REQUEST_SERIALIZATION_FAILED",
                        "Failed to serialize the ai-service budget-predict request.", e);
            }

            ResponseEntity<String> response;
            try {
                response = restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(requestBody, headers), String.class);
            } catch (HttpStatusCodeException e) {
                throw translateErrorEnvelope(e);
            } catch (ResourceAccessException e) {
                log.warn("ai-service unreachable at {}: {}", url, e.getMessage());
                throw new AiServiceCallException("AI_SERVICE_UNREACHABLE",
                        "Could not reach ai-service at " + url + ": " + e.getMessage(), e);
            }

            try {
                return aiServiceObjectMapper.readValue(response.getBody(), AiBudgetPredictResult.class);
            } catch (Exception e) {
                throw new AiServiceCallException("RESPONSE_PARSE_FAILED",
                        "Failed to parse ai-service budget-predict response.", e);
            }
        });
    }

    /**
     * ai-service's error envelope (SRS 20.6): {@code {error_code, message,
     * details}} - see {@code ai-service/app/core/exceptions.py}.
     */
    private AiServiceCallException translateErrorEnvelope(HttpStatusCodeException e) {
        String body = e.getResponseBodyAsString();
        try {
            var node = aiServiceObjectMapper.readTree(body);
            String errorCode = node.hasNonNull("error_code") ? node.get("error_code").asText() : "AI_SERVICE_ERROR";
            String message = node.hasNonNull("message") ? node.get("message").asText() : e.getMessage();
            return new AiServiceCallException(errorCode, message, e);
        } catch (Exception parseFailure) {
            return new AiServiceCallException("AI_SERVICE_ERROR",
                    "ai-service returned " + e.getStatusCode() + " with an unparseable body.", e);
        }
    }
}
