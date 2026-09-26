package com.jannetai.backend.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Audit GAP-018: under the {@code prod} profile the backend refuses to start
 * when a production setting is missing or unsafe (see
 * {@link ProductionSettingsCheck}), instead of running half-configured -
 * for example storing photos with a public link-signing secret or answering
 * CORS for localhost only. Warnings are logged and do not stop the start.
 * The JWT secret has its own check (JwtService, audit GAP-019).
 */
@Component
@Profile("prod")
public class ProductionConfigurationValidator {

    private static final Logger log = LoggerFactory.getLogger(ProductionConfigurationValidator.class);

    public ProductionConfigurationValidator(Environment environment) {
        ProductionSettingsCheck.Result result = ProductionSettingsCheck.check(environment::getProperty);
        result.warnings().forEach(w -> log.warn("Production configuration: {}", w));
        if (!result.errors().isEmpty()) {
            result.errors().forEach(e -> log.error("Production configuration: {}", e));
            throw new IllegalStateException("Refusing to start with an incomplete production configuration: "
                    + String.join("; ", result.errors()));
        }
        log.info("Production configuration check passed ({} warning(s))", result.warnings().size());
    }
}
