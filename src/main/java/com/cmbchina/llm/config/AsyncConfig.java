package com.cmbchina.llm.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步任务配置
 *
 * @author IT006901
 * @date 2026-08-26 09:00:00
 */
@Configuration
@EnableAsync
@Slf4j
public class AsyncConfig {

    /**
     * 插件改造异步执行线程池
     * 核心线程数设为1，避免多个Agent任务并发调用大模型导致网关超时
     */
    @Bean("agentTaskExecutor")
    public Executor agentTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("agent-async-");
        // 使用AbortPolicy拒绝策略，队列满时抛出RejectedExecutionException，避免阻塞调用线程
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        log.info("插件改造异步线程池初始化完成");
        return executor;
    }
}