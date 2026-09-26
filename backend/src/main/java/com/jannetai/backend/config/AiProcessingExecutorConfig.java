package com.jannetai.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Audit GAP-010: bounded pool for asynchronous AI processing. A full queue
 * rejects (AbortPolicy) instead of running on the request thread: the job row
 * is already persisted as PENDING, so the AiProcessingDispatcher sweeper picks
 * it up on its next run - no complaint is lost, and POST /complaints never
 * waits for ai-service.
 */
@Configuration
public class AiProcessingExecutorConfig {

    @Bean(name = "aiProcessingExecutor")
    public ThreadPoolTaskExecutor aiProcessingExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("ai-proc-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        executor.setTaskDecorator(new com.jannetai.backend.config.logging.MdcTaskDecorator()); // audit GAP-041: keep the request id
        return executor;
    }
}
