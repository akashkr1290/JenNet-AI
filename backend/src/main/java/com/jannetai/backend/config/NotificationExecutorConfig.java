package com.jannetai.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Gap-backlog Patch 17 (Sep 2026 strict recheck): bounded worker pool for
 * external notification delivery (see NotificationService#dispatch).
 * CallerRunsPolicy under saturation: when the queue is full, delivery falls
 * back to running on the submitting thread - it degrades to the old
 * synchronous behaviour under overload rather than dropping notifications.
 *
 * Honest scope vs the patch's SQS/RabbitMQ suggestion: this is in-process.
 * Retries happen in the worker and a message that exhausts them is persisted
 * as FAILED (queryable - the effective dead-letter record), but a task still
 * queued when the process stops is lost. A durable broker (SQS + DLQ) would
 * close that remaining gap and needs real AWS infrastructure.
 */
@Configuration
public class NotificationExecutorConfig {

    @Bean(name = "notificationExecutor")
    public ThreadPoolTaskExecutor notificationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("notify-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        executor.initialize();
        executor.setTaskDecorator(new com.jannetai.backend.config.logging.MdcTaskDecorator()); // audit GAP-041: keep the request id
        return executor;
    }
}
