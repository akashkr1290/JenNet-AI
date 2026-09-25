package com.jannetai.backend.exception;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Audit GAP-055: SRS 20.6 error_code is present alongside the existing error field. */
class ErrorResponseTest {

    @Test
    void serialisedErrorCarriesBothErrorAndErrorCode() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

        JsonNode json = mapper.readTree(mapper.writeValueAsString(
                ErrorResponse.of(400, "INVALID_OTP", "Incorrect OTP code", "/api/v1/auth/verify-otp")));

        assertThat(json.get("error").asText()).isEqualTo("INVALID_OTP");
        assertThat(json.get("error_code").asText()).isEqualTo("INVALID_OTP");
        assertThat(json.get("status").asInt()).isEqualTo(400);
    }
}
