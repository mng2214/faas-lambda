package com.faas;

import com.faas.model.FunctionEvent;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

import static com.faas.constants.RedisKeys.*;


/**
 * Реализация WorkerStorage на Redis.
 */
public class RedisWorkerStorage implements WorkerStorage {

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public RedisWorkerStorage(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @Override
    public FunctionEvent pollNextEvent(Duration timeout) throws Exception {
        String json = redis.opsForList().leftPop(EVENTS_QUEUE, timeout);
        if (json == null) {
            return null;
        }
        return objectMapper.readValue(json, FunctionEvent.class);
    }

    @Override
    public long getQueueLength() {
        Long size = redis.opsForList().size(EVENTS_QUEUE);
        return size != null ? size : 0L;
    }

    @Override
    public void incrementActive() {
        redis.opsForValue().increment(ACTIVE_INVOCATIONS);
    }

    @Override
    public void decrementActive() {
        redis.opsForValue().decrement(ACTIVE_INVOCATIONS);
    }

    @Override
    public void incrementProcessed() {
        redis.opsForValue().increment(PROCESSED_COUNT);
    }

    @Override
    public void incrementError() {
        redis.opsForValue().increment(ERROR_COUNT);
    }

    @Override
    public long getActiveCount() {
        String v = redis.opsForValue().get(ACTIVE_INVOCATIONS);
        return v != null ? Long.parseLong(v) : 0L;
    }

    @Override
    public long getProcessedCount() {
        String v = redis.opsForValue().get(PROCESSED_COUNT);
        return v != null ? Long.parseLong(v) : 0L;
    }

    @Override
    public long getErrorCount() {
        String v = redis.opsForValue().get(ERROR_COUNT);
        return v != null ? Long.parseLong(v) : 0L;
    }

    @Override
    public void storeResult(String functionName, String jsonResult) {
        String key = SUCCESS_LIST_PREFIX + functionName;
        redis.opsForList().rightPush(key, jsonResult);
    }

    @Override
    public void storeFunctionError(String functionName, String jsonError) {
        String key = FUNCTION_ERRORS_LIST_PREFIX + functionName;
        redis.opsForList().rightPush(key, jsonError);
    }

    @Override
    public void storeGlobalError(String jsonError) {
        redis.opsForList().rightPush(GLOBAL_ERRORS, jsonError);
    }
}
