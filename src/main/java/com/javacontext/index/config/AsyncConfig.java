package com.javacontext.index.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步处理线程池配置
 * 
 * 用于索引构建时的并行文件处理,提升索引速度3-5倍
 */
@Slf4j
@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * 索引构建线程池
     * 
     * 配置说明:
     * - 核心线程数: CPU核心数
     * - 最大线程数: CPU核心数 * 2
     * - 队列容量: 500 (待处理文件)
     * - 拒绝策略: 调用者运行 (避免任务丢失)
     */
    @Bean("indexTaskExecutor")
    public Executor indexTaskExecutor() {
        int cpuCores = Runtime.getRuntime().availableProcessors();
        int corePoolSize = cpuCores;
        int maxPoolSize = cpuCores * 2;
        
        log.info("初始化索引构建线程池: 核心线程={}, 最大线程={}, CPU核心={}", 
                corePoolSize, maxPoolSize, cpuCores);

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        
        // 核心线程数
        executor.setCorePoolSize(corePoolSize);
        
        // 最大线程数
        executor.setMaxPoolSize(maxPoolSize);
        
        // 队列容量
        executor.setQueueCapacity(500);
        
        // 线程名称前缀
        executor.setThreadNamePrefix("index-builder-");
        
        // 线程空闲时间 (秒)
        executor.setKeepAliveSeconds(60);
        
        // 拒绝策略: 调用者运行 (避免任务丢失)
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        
        // 等待所有任务结束后再关闭线程池
        executor.setWaitForTasksToCompleteOnShutdown(true);
        
        // 等待时间 (秒)
        executor.setAwaitTerminationSeconds(60);
        
        // 初始化
        executor.initialize();
        
        log.info("索引构建线程池初始化完成");
        
        return executor;
    }

    /**
     * Embedding批量处理线程池
     * 
     * 用于批量向量化处理
     */
    @Bean("embeddingTaskExecutor")
    public Executor embeddingTaskExecutor() {
        int cpuCores = Runtime.getRuntime().availableProcessors();
        
        log.info("初始化Embedding线程池: 核心线程={}, CPU核心={}", cpuCores, cpuCores);

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(cpuCores);
        executor.setMaxPoolSize(cpuCores);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("embedding-");
        executor.setKeepAliveSeconds(30);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        
        log.info("Embedding线程池初始化完成");
        
        return executor;
    }
}
