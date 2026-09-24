package com.jannetai.backend.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * Phase 8: the single outbound HTTP client this backend needs, for calling
 * {@code ai-service} (ARCHITECTURE.md's 2-service architecture - Spring
 * Boot calling Python server-to-server). Plain {@link RestTemplate} rather
 * than {@code WebClient}/reactive stack: this project's dependency set
 * (pom.xml) already includes {@code spring-boot-starter-web}, which is all
 * {@link RestTemplateBuilder} needs; adding {@code spring-webflux} just for
 * one blocking, synchronous call would be exactly the kind of
 * overengineering ARCHITECTURE.md Section 7 asks this project to avoid.
 */
@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate aiServiceRestTemplate(RestTemplateBuilder builder, AiServiceProperties aiServiceProperties) {
        return builder
                .setConnectTimeout(Duration.ofMillis(aiServiceProperties.getConnectTimeoutMs()))
                .setReadTimeout(Duration.ofMillis(aiServiceProperties.getReadTimeoutMs()))
                .build();
    }
}
