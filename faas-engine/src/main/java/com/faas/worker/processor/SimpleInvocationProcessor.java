package com.faas.worker.processor;

import com.faas.LocalLambdaFunction;
import com.faas.config.LambdaWorkerConfig;
import com.faas.dto.EventRequest;
import com.faas.storage.WorkerStorage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * SIMPLE / WEBHOOK processing.
 */
@Slf4j
public class SimpleInvocationProcessor {

    private static final String KEY_CALLBACK_URL = "_callbackUrl";

    private final WorkerStorage storage;
    private final LambdaWorkerConfig config;
    private final ObjectMapper objectMapper;
    private final Runnable onInvocationFinished;

    public SimpleInvocationProcessor(WorkerStorage storage,
                                     LambdaWorkerConfig config,
                                     ObjectMapper objectMapper,
                                     Runnable onInvocationFinished) {
        this.storage = storage;
        this.config = config;
        this.objectMapper = objectMapper;
        this.onInvocationFinished = onInvocationFinished;
    }

    public void processSimple(EventRequest event,
                              LocalLambdaFunction function,
                              Map<String, Object> payload,
                              boolean webhookMode) {
        String functionName = event.getFunctionName();
        int maxAttempts = resolveMaxAttempts(function);

        try {
            int attempt = 0;
            while (attempt < maxAttempts) {
                attempt++;
                Instant start = Instant.now();
                try {
                    Map<String, Object> output = function.handle(payload);

                    storage.incrementProcessed();

                    Map<String, Object> record = new HashMap<>();
                    record.put("eventId", event.getEventId());
                    record.put("functionName", functionName);
                    record.put("output", output);
                    record.put("status", "success");
                    record.put("timestamp", Instant.now().toString());
                    record.put("durationMs",
                            Duration.between(start, Instant.now()).toMillis());

                    // callbackUrl support
                    Object callbackUrl = payload.get(KEY_CALLBACK_URL);
                    if (callbackUrl != null) {
                        record.put("callbackUrl", callbackUrl);
                    }
                    if (webhookMode) {
                        record.put("delivery", "WEBHOOK");
                    }

                    storage.storeResult(functionName, toJson(record));
                    return;
                } catch (Exception ex) {
                    log.warn("Function '{}' attempt {}/{} failed",
                            functionName, attempt, maxAttempts, ex);
                    if (attempt >= maxAttempts) {
                        markFunctionError(event, functionName, ex);
                    } else {
                        Thread.sleep(10L * attempt); // backoff
                    }
                }
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("Processing of '{}' interrupted", functionName);
        } finally {
            onInvocationFinished.run();
        }
    }

    private int resolveMaxAttempts(LocalLambdaFunction fn) {
        int fnMax = fn.maxRetries();
        int cfgMax = config.maxRetries();
        if (fnMax <= 0 && cfgMax <= 0) {
            return 1;
        }
        if (fnMax <= 0) {
            return cfgMax;
        }
        if (cfgMax <= 0) {
            return fnMax;
        }
        return Math.min(fnMax, cfgMax);
    }

    private String toJson(Object value) throws JsonProcessingException {
        return objectMapper.writeValueAsString(value);
    }

    private void markFunctionError(EventRequest event, String functionName, Exception ex) {
        storage.incrementError();
        tryStoreFunctionError(event, functionName, ex);
    }

    private void tryStoreFunctionError(EventRequest event, String functionName, Exception ex) {
        try {
            Map<String, Object> error = new HashMap<>();
            error.put("eventId", event.getEventId());
            error.put("functionName", functionName);
            error.put("errorMessage", ex.getMessage());
            error.put("timestamp", Instant.now().toString());
            storage.storeFunctionError(functionName, toJson(error));
        } catch (Exception e) {
            log.error("Failed to store function error for '{}'", functionName, e);
        }
    }
}
