package com.jannetai.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** Audit GAP-021: audit details are always valid JSON, whatever staff type. */
class AuditJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void controlCharactersBackslashesAndQuotesSurviveARoundTrip() throws Exception {
        String note = "Line 1\nLine 2\r\n\tIndented \\ path \"quoted\" \u0001 bell";
        JsonNode json = mapper.readTree(AuditJson.of("note", note));
        assertThat(json.get("note").asText()).isEqualTo(note);
    }

    @Test
    void numbersBooleansEnumsAndNullsKeepTheirJsonTypes() throws Exception {
        JsonNode json = mapper.readTree(AuditJson.of("n", 72, "b", true, "d", new BigDecimal("12.50"),
                "e", java.time.DayOfWeek.MONDAY, "z", null));
        assertThat(json.get("n").isInt()).isTrue();
        assertThat(json.get("b").asBoolean()).isTrue();
        assertThat(json.get("d").decimalValue()).isEqualByComparingTo("12.50");
        assertThat(json.get("e").asText()).isEqualTo("MONDAY");
        assertThat(json.get("z").isNull()).isTrue();
    }
}
