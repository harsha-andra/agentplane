package com.harshaandra.agentplane.config;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Backs the pod-status watcher, the (simulated, in {@code NoopJobLauncher}) run lifecycle, and
 * SSE heartbeat/broadcast work - none of which should run on request threads.
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig implements AsyncConfigurer {

    @Override
    @Bean(name = "agentplaneTaskExecutor")
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("agentplane-async-");
        executor.initialize();
        return executor;
    }

    /**
     * Used by {@code orchestration.NoopJobLauncher} to simulate a run's lifecycle (scheduled ->
     * running -> succeeded/failed) with delayed callbacks when no real cluster is present, and
     * available generally for any one-off delayed work.
     */
    @Bean
    public TaskScheduler agentplaneTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("agentplane-sched-");
        scheduler.initialize();
        return scheduler;
    }
}
