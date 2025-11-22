package com.faas.api;

import com.faas.model.FunctionEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

import static com.faas.constants.RedisKeys.EVENTS_QUEUE;


@RestController
@RequestMapping("/events")
public class EventController {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;


    public EventController(StringRedisTemplate redisTemplate,
                                  ObjectMapper objectMapper
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;

    }

    @PostMapping("/{functionName}")
    public ResponseEntity<?> enqueue(@PathVariable String functionName,
                                     @RequestBody(required = false) Map<String, Object> payload) {

        // ✅ Проверка существования функции ТОЛЬКО в контроллере, как ты и просил
//        if (!functionRegistry.exists(functionName)) {
//            return ResponseEntity.status(HttpStatus.NOT_FOUND)
//                    .body(Map.of(
//                            "status", "ERROR",
//                            "message", "Function '" + functionName + "' is not registered"
//                    ));
//        }

        String eventId = UUID.randomUUID().toString();
        FunctionEvent event = new FunctionEvent(
                eventId,
                functionName,
                payload != null ? payload : Map.of()
        );

        String json;
        try {
            json = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "status", "ERROR",
                            "message", "Failed to serialize event: " + e.getMessage()
                    ));
        }

        redisTemplate.opsForList().rightPush(EVENTS_QUEUE, json);

        Map<String, Object> responseBody = Map.of(
                "eventId", eventId,
                "status", "ENQUEUED",
                "message", "Event accepted and queued for processing"
        );
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(responseBody);
    }
}
