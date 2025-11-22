package com.faas;

import com.faas.model.FunctionEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

import static com.faas.constants.RedisKeys.EVENTS_QUEUE;


/**
 * ПЛАТФОРМА:
 * - знает, какие функции зарегистрированы
 * - умеет ставить события в очередь
 * - предоставляет метод проверки существования функции
 */
@Service
public class LocalLambdaPlatform {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final FunctionRegistry functionRegistry;

    public LocalLambdaPlatform(StringRedisTemplate redisTemplate,
                               ObjectMapper objectMapper,
                               FunctionRegistry functionRegistry) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.functionRegistry = functionRegistry;
    }

    /**
     * Проверка существования функции по имени.
     */
    public boolean functionExists(String functionName) {
        return functionRegistry.exists(functionName);
    }

    /**
     * Поставить событие в очередь.
     * Если функции нет — кидаем своё исключение.
     *
     * @return eventId, по которому можно трекать результат
     */
    public String enqueueEvent(String functionName,
                               Map<String, Object> payload) {

        if (!functionRegistry.exists(functionName)) {
            throw new RuntimeException(functionName);
        }

        String eventId = UUID.randomUUID().toString();

        FunctionEvent event = new FunctionEvent(
                eventId,
                functionName,
                payload != null ? payload : Map.of()
        );

        String json = serializeEvent(event);

        redisTemplate.opsForList().rightPush(EVENTS_QUEUE, json);

        return eventId;
    }

    private String serializeEvent(FunctionEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize event", e);
        }
    }
}
