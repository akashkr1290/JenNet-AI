package com.jannetai.backend.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Audit GAP-026 (SRS 20.6: "All timestamps are returned in ISO 8601 UTC format").
 *
 * Every entity/DTO timestamp is a {@link LocalDateTime}. Connector/J and
 * {@code LocalDateTime.now()} both use the JVM default time zone, which
 * {@link com.jannetai.backend.BackendApplication} pins to UTC - so these values
 * ARE UTC wall-clock times. They used to be serialised without a zone
 * designator ("2026-09-25T10:00:00"), which Dart and browsers parse as LOCAL
 * time, so Indian users saw every time 5 h 30 min early. This serialiser adds
 * the "Z" designator. Only the API response format changes; request DTOs have
 * no LocalDateTime fields, and the ai-service client uses its own ObjectMapper.
 */
@Configuration
public class UtcDateTimeConfig {

    static final class UtcLocalDateTimeSerializer extends JsonSerializer<LocalDateTime> {
        @Override
        public void serialize(LocalDateTime value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            gen.writeString(DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(value) + "Z");
        }
    }

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer utcLocalDateTimeCustomizer() {
        return builder -> builder.serializerByType(LocalDateTime.class, new UtcLocalDateTimeSerializer());
    }
}
