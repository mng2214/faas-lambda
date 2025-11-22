package com.faas.core;

import com.faas.metrics.RedisMetricsService;
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

/**
 * High-level facade over the FaaS engine:
 * - submits events into the queue (enqueueEvent)
 * - provides a function catalog (listFunctions)
 * - exposes system metrics (getSystemMetrics)
 * - reads results and errors from Redis
 * <p>
 * This is the main entry point that your external service/controller
 * should interact with.
 */

@Service
public class LocalLambdaPlatform {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final FunctionRegistry functionRegistry;
    private final RedisMetricsService metricsService;

    public LocalLambdaPlatform(StringRedisTemplate redisTemplate,
                               ObjectMapper objectMapper,
                               FunctionRegistry functionRegistry,
                               RedisMetricsService metricsService) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.functionRegistry = functionRegistry;
        this.metricsService = metricsService;
    }

// ---------------------------------------------------------------------
// Functions: catalog and existence check
// ---------------------------------------------------------------------

    public boolean functionExists(String functionName) {
        return functionRegistry.exists(functionName);
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

// ---------------------------------------------------------------------
// Event publication into the queue
// ---------------------------------------------------------------------

    public String enqueueEvent(String functionName,
                               Map<String, Object> payload) {

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

    // ---------------------------------------------------------------------
    // Metrics
    // ---------------------------------------------------------------------

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

// ---------------------------------------------------------------------
// Reading lists from Redis (generic helper + specific utilities)
// ---------------------------------------------------------------------


    public List<String> getListForKey(String key, int page, int size) {
        long start = (long) page * size;
        long end = start + size - 1;
        return redisTemplate.opsForList().range(key, start, end);
    }

    public List<String> getFunctionResults(String functionName, int page, int size) {
        String key = SUCCESS_LIST_PREFIX + functionName;
        return getListForKey(key, page, size);
    }

    public List<String> getFunctionErrors(String functionName, int page, int size) {
        String key = FUNCTION_ERRORS_LIST_PREFIX + functionName;
        return getListForKey(key, page, size);
    }
}
