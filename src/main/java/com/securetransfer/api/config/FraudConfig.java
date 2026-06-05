package com.securetransfer.api.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Wires up the fraud-triage feature: binds the config properties and enables
 * async execution so a flagged transfer's review runs in the background (the
 * transfer response is never held up waiting on the AI).
 */
@Configuration
@EnableAsync
@EnableConfigurationProperties({FraudProperties.class, AnthropicProperties.class})
public class FraudConfig {

    /** Bounded executor for async fraud reviews. */
    @Bean(name = "fraudReviewExecutor")
    public Executor fraudReviewExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("fraud-review-");
        // Backpressure instead of dropping: if the pool + queue are full, the
        // submitting (post-commit) thread runs the review itself. The transfer
        // has already committed, so this only throttles the review — a flagged
        // transfer's review is never silently discarded.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
