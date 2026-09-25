package com.jannetai.backend.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Audit GAP-026: API timestamps carry the UTC designator. */
class UtcDateTimeConfigTest {

    @Test
    void localDateTimeIsSerialisedAsIsoUtcWithZ() throws Exception {
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        new UtcDateTimeConfig().utcLocalDateTimeCustomizer().customize(builder);
        ObjectMapper mapper = builder.build();

        String json = mapper.writeValueAsString(Map.of("createdAt", LocalDateTime.of(2026, 9, 25, 10, 0, 0)));

        assertThat(json).isEqualTo("{\"createdAt\":\"2026-09-25T10:00:00Z\"}");
    }
}
