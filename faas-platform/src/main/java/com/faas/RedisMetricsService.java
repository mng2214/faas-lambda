package com.faas;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import static com.faas.constants.RedisKeys.*;


@Service
public class RedisMetricsService {

    private final StringRedisTemplate redis;

    public RedisMetricsService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public long getActiveInvocations() {
        String v = redis.opsForValue().get(ACTIVE_INVOCATIONS);
        return v != null ? Long.parseLong(v) : 0L;
    }

    public long getProcessedCount() {
        String v = redis.opsForValue().get(PROCESSED_COUNT);
        return v != null ? Long.parseLong(v) : 0L;
    }

    public long getErrorCount() {
        String v = redis.opsForValue().get(ERROR_COUNT);
        return v != null ? Long.parseLong(v) : 0L;
    }

    public long getQueueLength() {
        Long size = redis.opsForList().size(EVENTS_QUEUE);
        return size != null ? size : 0L;
    }
}
