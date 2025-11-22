package com.faas.metrics;

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
        return getLongValue(ACTIVE_INVOCATIONS);
    }

    public long getProcessedCount() {
        return getLongValue(PROCESSED_COUNT);
    }

    public long getErrorCount() {
        return getLongValue(ERROR_COUNT);
    }

    public long getQueueLength() {
        Long size = redis.opsForList().size(EVENTS_QUEUE);
        return size != null ? size : 0L;
    }

    private long getLongValue(String key) {
        String v = redis.opsForValue().get(key);
        return v != null ? Long.parseLong(v) : 0L;
    }
}
