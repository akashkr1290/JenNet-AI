package com.jannetai.backend.service.complaint;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Audit GAP-010: retry classification and backoff. */
class AiRetryPolicyTest {

    @Test
    void transientFailuresAreRetriedUpToTheLimit() {
        assertThat(AiRetryPolicy.shouldRetry("AI_SERVICE_UNREACHABLE", 1, 3)).isTrue();
        assertThat(AiRetryPolicy.shouldRetry("MODEL_TIMEOUT", 2, 3)).isTrue();
        assertThat(AiRetryPolicy.shouldRetry("AI_SERVICE_ERROR", 3, 3)).isFalse();
    }

    @Test
    void anUnusableImageOrAConfigurationErrorIsNeverRetried() {
        assertThat(AiRetryPolicy.shouldRetry("UNPROCESSABLE_IMAGE", 1, 3)).isFalse();
        assertThat(AiRetryPolicy.shouldRetry("UNAUTHORIZED", 1, 3)).isFalse();
        assertThat(AiRetryPolicy.shouldRetry("NO_IMAGE_ON_FILE", 1, 3)).isFalse();
    }

    @Test
    void backoffDoublesAndIsCapped() {
        assertThat(AiRetryPolicy.backoffSeconds(1, 30)).isEqualTo(30);
        assertThat(AiRetryPolicy.backoffSeconds(2, 30)).isEqualTo(60);
        assertThat(AiRetryPolicy.backoffSeconds(3, 30)).isEqualTo(120);
        assertThat(AiRetryPolicy.backoffSeconds(40, 30)).isEqualTo(3600);
    }
}
