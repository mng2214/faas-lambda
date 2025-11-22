package com.faas.config;

import com.faas.LocalLambdaFunction;
import com.faas.storage.RedisWorkerStorage;
import com.faas.registry.FunctionRegistry;
import com.faas.storage.WorkerStorage;
import com.faas.worker.QueueWorker;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

@Configuration
public class EngineConfig {

    @Bean
    public WorkerStorage workerStorage(StringRedisTemplate redisTemplate,
                                       ObjectMapper objectMapper) {
        return new RedisWorkerStorage(redisTemplate, objectMapper);
    }

    @Bean
    public FunctionRegistry functionRegistry(List<LocalLambdaFunction> functionBeans) {
        System.out.println("### FOUND FUNCTIONS: " + functionBeans.size());
        FunctionRegistry registry = new FunctionRegistry();
        registry.registerAll(functionBeans);
        return registry;
    }

    @Bean
    public LambdaWorkerConfig lambdaWorkerConfig(LambdaWorkerProperties props) {
        LambdaWorkerProperties.Worker w = props.getWorker();
        LambdaWorkerProperties.Lambda l = props.getLambda();
        return new LambdaWorkerConfig(
                w.isEnabled(),
                w.getInitialDelay(),
                w.getTimeoutSeconds(),
                l.getMaxRetries(),
                l.getMaxWorkerThreads(),
                l.getMaxConcurrent()
        );
    }

    @Bean
    public QueueWorker queueWorker(WorkerStorage storage,
                                   FunctionRegistry functionRegistry,
                                   LambdaWorkerConfig config,
                                   ObjectMapper objectMapper) {
        return new QueueWorker(storage, functionRegistry, config, objectMapper);
    }
}
