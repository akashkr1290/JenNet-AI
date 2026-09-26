package com.jannetai.backend.config;

import jakarta.persistence.EntityManagerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;

/**
 * Audit GAP-041 (SRS 26 transient retry x3 with exponential backoff).
 *
 * Retries only the START of a transaction - obtaining the database
 * connection - when it fails transiently (see {@link TransientFailures}).
 * Nothing has been executed at that point, so repeating it is always safe;
 * work that fails half-way inside a transaction is never replayed, because
 * that could apply a non-idempotent change twice. Every @Transactional
 * service method and Spring Data repository call goes through this manager.
 *
 * On a failed begin, JpaTransactionManager closes the EntityManager it had
 * created and clears it from the transaction object, so the next attempt
 * starts clean.
 */
public class RetryingJpaTransactionManager extends JpaTransactionManager {

    private static final Logger log = LoggerFactory.getLogger(RetryingJpaTransactionManager.class);

    private final int maxRetries;
    private final long baseDelayMs;

    public RetryingJpaTransactionManager(EntityManagerFactory entityManagerFactory, int maxRetries, long baseDelayMs) {
        super(entityManagerFactory);
        this.maxRetries = Math.max(0, maxRetries);
        this.baseDelayMs = Math.max(0, baseDelayMs);
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        int retries = 0;
        while (true) {
            try {
                super.doBegin(transaction, definition);
                return;
            } catch (TransactionException ex) {
                if (retries >= maxRetries || !TransientFailures.isTransient(ex)) {
                    throw ex;
                }
                retries++;
                long delay = TransientFailures.backoffMillis(baseDelayMs, retries);
                log.warn("Transient database failure while starting a transaction - retry {}/{} in {} ms ({})",
                        retries, maxRetries, delay, ex.getClass().getSimpleName());
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw ex;
                }
            }
        }
    }

    int getMaxRetries() {
        return maxRetries;
    }
}
