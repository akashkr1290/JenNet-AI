package com.jannetai.backend.config;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Audit GAP-041: replaces Spring Boot's auto-configured JpaTransactionManager
 * (which backs off when a TransactionManager bean exists) with
 * {@link RetryingJpaTransactionManager}. Same bean name, so repositories and
 * @Transactional keep using it without changes.
 */
@Configuration
public class TransactionRetryConfig {

    @Bean(name = "transactionManager")
    public PlatformTransactionManager transactionManager(
            EntityManagerFactory entityManagerFactory,
            @Value("${app.database.transient-retry.max-retries:3}") int maxRetries,
            @Value("${app.database.transient-retry.base-delay-ms:200}") long baseDelayMs) {
        return new RetryingJpaTransactionManager(entityManagerFactory, maxRetries, baseDelayMs);
    }
}
