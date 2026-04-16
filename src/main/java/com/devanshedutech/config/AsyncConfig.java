package com.devanshedutech.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean("taskExecutor") // Was missing — without this, @Async tasks run on the main thread!
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);        // Minimum threads
        executor.setMaxPoolSize(20);       // Max threads under heavy I/O
        executor.setQueueCapacity(500);    // Queue capacity before rejecting tasks
        executor.setThreadNamePrefix("HeavyIO-Thread-");
        executor.initialize();
        return executor;
    }
}
