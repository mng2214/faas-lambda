package com.faas.worker.processor;

import com.faas.LocalLambdaFunction;
import com.faas.config.LambdaWorkerConfig;
import com.faas.dto.EventRequest;
import com.faas.registry.FunctionRegistry;
import com.faas.storage.WorkerStorage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CHAIN mode:
 * payload._chain = ["f1", "f2", "f3"]
 * The result of fn1 → becomes input for fn2 → becomes input for fn3
 */
@Slf4j
public class ChainInvocationProcessor {

    private static final String KEY_MODE = "_mode";
    private static final String KEY_CHAIN = "_chain";
    private static final String KEY_CALLBACK_URL = "_callbackUrl";

    private final WorkerStorage storage;
    private final FunctionRegistry functionRegistry;
    private final LambdaWorkerConfig config;
    private final ObjectMapper objectMapper;
    private final SimpleInvocationProcessor simpleProcessor;
    private final Runnable onInvocationFinished;

    public ChainInvocationProcessor(WorkerStorage storage,
                                    FunctionRegistry functionRegistry,
                                    LambdaWorkerConfig config,
                                    ObjectMapper objectMapper,
                                    SimpleInvocationProcessor simpleProcessor,
                                    Runnable onInvocationFinished) {
        this.storage = storage;
        this.functionRegistry = functionRegistry;
        this.config = config;
        this.objectMapper = objectMapper;
        this.simpleProcessor = simpleProcessor;
        this.onInvocationFinished = onInvocationFinished;
    }

    public void processChain(EventRequest event, Map<String, Object> payload) {
        Object rawChain = payload.get(KEY_CHAIN);
        if (!(rawChain instanceof List<?> chainList) || chainList.isEmpty()) {
            log.warn("CHAIN mode event '{}' has no '{}' list", event.getEventId(), KEY_CHAIN);
            // fallback – regular func
            LocalLambdaFunction fn = functionRegistry.get(event.getFunctionName());
            if (fn == null) {
                storage.incrementError();
                tryStoreGlobalError("Function not found for CHAIN fallback: " + event.getEventId(), null);
                onInvocationFinished.run();
                return;
            }
            simpleProcessor.processSimple(event, fn, payload, false);
            return;
        }

        Map<String, Object> current = new HashMap<>(payload);
        // remove control fields before the first step
        current.remove(KEY_MODE);
        current.remove(KEY_CHAIN);

        String pipelineName = event.getFunctionName();

        try {
            for (Object stepObj : chainList) {
                if (!(stepObj instanceof String stepName)) {
                    log.warn("Invalid step name in chain for event '{}': {}", event.getEventId(), stepObj);
                    storage.incrementError();
                    tryStoreGlobalError("Invalid chain step: " + stepObj, null);
                    onInvocationFinished.run();
                    return;
                }

                LocalLambdaFunction stepFn = functionRegistry.get(stepName);
                if (stepFn == null) {
                    log.warn("Chain step '{}' not found", stepName);
                    storage.incrementError();
                    tryStoreGlobalError("Chain step not found: " + stepName, null);
                    onInvocationFinished.run();
                    return;
                }

                int maxAttempts = resolveMaxAttempts(stepFn);
                int attempt = 0;
                boolean success = false;

                while (attempt < maxAttempts && !success) {
                    attempt++;
                    try {
                        current = stepFn.handle(current);
                        success = true;
                    } catch (Exception ex) {
                        log.warn("Chain step '{}' attempt {}/{} failed",
                                stepName, attempt, maxAttempts, ex);
                        if (attempt >= maxAttempts) {
                            tryStoreFunctionError(event, stepName, ex);
                            storage.incrementError();
                            onInvocationFinished.run();
                            return;
                        } else {
                            Thread.sleep(10L * attempt);
                        }
                    }
                }
            }

            storage.incrementProcessed();

            Map<String, Object> record = new HashMap<>();
            record.put("eventId", event.getEventId());
            record.put("functionName", pipelineName);
            record.put("output", current);
            record.put("status", "success");
            record.put("timestamp", Instant.now().toString());
            record.put("chain", rawChain);

            Object callbackUrl = payload.get(KEY_CALLBACK_URL);
            if (callbackUrl != null) {
                record.put("callbackUrl", callbackUrl);
            }

            storage.storeResult(pipelineName, toJson(record));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("CHAIN for '{}' interrupted", pipelineName);
        } catch (Exception ex) {
            log.error("CHAIN for '{}' failed", pipelineName, ex);
            storage.incrementError();
            tryStoreFunctionError(event, pipelineName, ex);
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

    private void tryStoreGlobalError(String message, Exception ex) {
        try {
            Map<String, Object> error = new HashMap<>();
            error.put("message", message);
            error.put("timestamp", Instant.now().toString());
            if (ex != null) {
                error.put("errorMessage", ex.getMessage());
            }
            storage.storeGlobalError(toJson(error));
        } catch (Exception e) {
            log.error("Failed to store global error", e);
        }
    }
}
