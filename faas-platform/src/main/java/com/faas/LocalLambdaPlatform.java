package com.faas;

import com.faas.dto.EventRequest;
import com.faas.dto.FunctionInfo;
import com.faas.registry.FunctionRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.faas.constants.RedisKeys.*;


@Service
public class LocalLambdaPlatform {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final FunctionRegistry functionRegistry;
    private final RedisMetricsService metricsService;

    public LocalLambdaPlatform(StringRedisTemplate redisTemplate,
                               ObjectMapper objectMapper,
                               FunctionRegistry functionRegistry, RedisMetricsService metricsService) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.functionRegistry = functionRegistry;
        this.metricsService = metricsService;
    }

    public boolean functionExists(String functionName) {
        return functionRegistry.exists(functionName);
    }

    public String enqueueEvent(String functionName,
                               Map<String, Object> payload) {

        if (!functionRegistry.exists(functionName)) {
            throw new RuntimeException(functionName);
        }

        String eventId = UUID.randomUUID().toString();

        EventRequest event = new EventRequest(
                eventId,
                functionName,
                payload != null ? payload : Map.of()
        );

        String json = serializeEvent(event);

        redisTemplate.opsForList().rightPush(EVENTS_QUEUE, json);

        return eventId;
    }

    private String serializeEvent(EventRequest event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize event", e);
        }
    }

    public List<FunctionInfo> listFunctions() {
        return functionRegistry.getAll().values().stream()
                .map(fn -> {
                    FunctionInfo info = new FunctionInfo();
                    info.setName(fn.getName());
                    info.setDisplayName(fn.displayName());
                    info.setDescription(fn.description());
                    info.setWorkloadType(fn.workloadType());
                    info.setMaxRetries(fn.maxRetries());
                    return info;
                })
                .toList();
    }


    public Map<String, Object> getSystemMetrics() {
        long queueLength = metricsService.getQueueLength();
        long active = metricsService.getActiveInvocations();
        long processed = metricsService.getProcessedCount();
        long errors = metricsService.getErrorCount();

        return Map.of(
                "queueLength", queueLength,
                "activeInvocations", active,
                "processedCount", processed,
                "errorCount", errors
        );
    }


    public List<String> getListForKey(String key, int page, int size) {
        long start = (long) page * size;
        long end = start + size - 1;
        return redisTemplate.opsForList().range(key, start, end);
    }

    public List<String> getFunctionResults(String functionName, int page, int size) {
        String key = SUCCESS_LIST_PREFIX + functionName;
        long start = (long) page * size;
        long end = start + size - 1;

        return redisTemplate.opsForList().range(key, start, end);
    }


    public List<String> getFunctionErrors(String functionName, int page, int size) {
        String key = FUNCTION_ERRORS_LIST_PREFIX + functionName;
        long start = (long) page * size;
        long end = start + size - 1;

        return redisTemplate.opsForList().range(key, start, end);
    }

}
