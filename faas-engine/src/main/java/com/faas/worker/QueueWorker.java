package com.faas.worker;

import com.faas.config.LambdaWorkerConfig;
import com.faas.dto.EventRequest;
import com.faas.enums.WorkloadType;
import com.faas.function.LocalLambdaFunction;
import com.faas.registry.FunctionRegistry;
import com.faas.storage.WorkerStorage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Core queue worker that:
 * - polls events from {@link WorkerStorage}
 * - routes them to appropriate function from {@link FunctionRegistry}
 * - supports SIMPLE / STREAM / WEBHOOK / CHAIN modes
 * <p>
 * Backward compatible:
 * - If no special fields are present in payload, event is processed in SIMPLE mode.
 * - WorkerStorage got only *optional* default methods for streaming support.
 */
@Slf4j
public class QueueWorker {

    private static final String KEY_MODE = "_mode";              // SIMPLE / STREAM / WEBHOOK / CHAIN
    private static final String KEY_CHAIN = "_chain";            // List<String> of function names
    private static final String KEY_CALLBACK_URL = "_callbackUrl";
    private static final String KEY_STREAM_ID = "_streamId";

    private final WorkerStorage storage;
    private final FunctionRegistry functionRegistry;
    private final LambdaWorkerConfig config;
    private final ObjectMapper objectMapper;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger concurrentInvocations = new AtomicInteger(0);

    private ExecutorService workerExecutor;
    private ExecutorService cpuExecutor;
    private ExecutorService ioExecutor;

    public QueueWorker(WorkerStorage storage,
                       FunctionRegistry functionRegistry,
                       LambdaWorkerConfig config,
                       ObjectMapper objectMapper) {
        this.storage = storage;
        this.functionRegistry = functionRegistry;
        this.config = config;
        this.objectMapper = objectMapper;
    }

    public void start() {
        if (!config.enabled()) {
            log.warn("QueueWorker is disabled via configuration.");
            return;
        }

        if (!running.compareAndSet(false, true)) {
            log.info("QueueWorker already running.");
            return;
        }

        int maxWorkers = Math.max(1, config.maxWorkerThreads());
        this.workerExecutor = Executors.newFixedThreadPool(
                maxWorkers,
                r -> {
                    Thread t = new Thread(r);
                    t.setName("faas-queue-worker-" + t.getId());
                    t.setDaemon(false);
                    return t;
                }
        );

        this.cpuExecutor = Executors.newFixedThreadPool(
                Runtime.getRuntime().availableProcessors(),
                new NamedThreadFactory("faas-cpu-")
        );

        this.ioExecutor = Executors.newCachedThreadPool(
                new NamedThreadFactory("faas-io-")
        );

        // warm-up delay
        if (config.initialDelayMs() > 0) {
            try {
                Thread.sleep(config.initialDelayMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        log.info("Starting QueueWorker with {} poller threads", maxWorkers);
        for (int i = 0; i < maxWorkers; i++) {
            final int id = i + 1;
            workerExecutor.submit(() -> startWorkerLoop(id));
        }
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        log.info("Stopping QueueWorker...");

        if (workerExecutor != null) {
            workerExecutor.shutdownNow();
        }
        if (cpuExecutor != null) {
            cpuExecutor.shutdown();
        }
        if (ioExecutor != null) {
            ioExecutor.shutdown();
        }
    }

    private void startWorkerLoop(int id) {
        log.info("Worker-{} started", id);
        Duration pollTimeout = Duration.ofSeconds(Math.max(1, config.pollTimeoutSeconds()));
        int maxConcurrency = Math.max(1, config.maxConcurrentInvocations());

        while (running.get()) {
            try {
                // simple back-pressure based on concurrent invocations
                if (concurrentInvocations.get() >= maxConcurrency) {
                    Thread.sleep(5);
                    continue;
                }

                EventRequest event = storage.pollNextEvent(pollTimeout);
                if (event == null) {
                    continue;
                }

                concurrentInvocations.incrementAndGet();
                storage.incrementActive();

                dispatchEvent(event);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.warn("Worker-{} interrupted, exiting loop", id);
                break;
            } catch (Exception ex) {
                log.error("Worker-{} failed to poll event", id, ex);
                // global storage error marker
                tryStoreGlobalError("Failed to poll event", ex);
            }
        }

        log.info("Worker-{} stopped", id);
    }

    private void dispatchEvent(EventRequest event) {
        // determine function
        LocalLambdaFunction function = functionRegistry.get(event.getFunctionName());
        if (function == null) {
            log.warn("No function found for name '{}'", event.getFunctionName());
            storage.incrementError();
            tryStoreGlobalError("Function not found: " + event.getFunctionName(), null);
            decrementActive();
            return;
        }

        Map<String, Object> payload = safePayload(event);

        String modeRaw = (String) payload.getOrDefault(KEY_MODE, "SIMPLE");
        String mode = modeRaw == null ? "SIMPLE" : modeRaw.toUpperCase(Locale.ROOT);

        // choose executor based on workload type
        ExecutorService targetExecutor =
                function.workloadType() == WorkloadType.CPU_BOUND ? cpuExecutor : ioExecutor;

        switch (mode) {
            case "STREAM", "STREAMING" -> targetExecutor.submit(() -> processStream(event, function, payload));
            case "CHAIN" -> targetExecutor.submit(() -> processChain(event, payload));
            case "WEBHOOK" -> targetExecutor.submit(() -> processSimple(event, function, payload, true));
            default -> targetExecutor.submit(() -> processSimple(event, function, payload, false));
        }
    }

    // SIMPLE and WEBHOOK share same core logic; WEBHOOK = SIMPLE + callbackUrl in result
    private void processSimple(EventRequest event,
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

                    // callbackUrl support for webhook mode (или если пользователь просто положил его в payload)
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
                        Thread.sleep(10L * attempt); // small backoff
                    }
                }
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("Processing of '{}' interrupted", functionName);
        } finally {
            decrementActive();
        }
    }

    private void processStream(EventRequest event,
                               LocalLambdaFunction function,
                               Map<String, Object> payload) {
        String functionName = event.getFunctionName();
        String streamId = (String) payload.get(KEY_STREAM_ID);
        if (!(function instanceof LocalLambdaFunction.Streaming streamingFn)) {
            log.warn("Function '{}' is not LocalLambdaFunction.Streaming, falling back to SIMPLE", functionName);
            processSimple(event, function, payload, false);
            return;
        }

        try {
            LocalLambdaFunction.Streaming.StreamEmitter emitter =
                    new LocalLambdaFunction.Streaming.StreamEmitter() {
                        @Override
                        public void next(Object chunk) {
                            try {
                                Map<String, Object> record = new HashMap<>();
                                record.put("eventId", event.getEventId());
                                record.put("functionName", functionName);
                                record.put("chunk", chunk);
                                record.put("timestamp", Instant.now().toString());
                                if (streamId != null) {
                                    record.put("streamId", streamId);
                                }
                                Object callbackUrl = payload.get(KEY_CALLBACK_URL);
                                if (callbackUrl != null) {
                                    record.put("callbackUrl", callbackUrl);
                                }
                                storage.appendStreamChunk(
                                        streamId != null ? streamId : event.getEventId(),
                                        toJson(record)
                                );
                            } catch (Exception e) {
                                log.error("Failed to store stream chunk for '{}'", functionName, e);
                            }
                        }

                        @Override
                        public void complete() {
                            try {
                                storage.completeStream(
                                        streamId != null ? streamId : event.getEventId()
                                );
                            } catch (Exception e) {
                                log.error("Failed to mark stream complete for '{}'", functionName, e);
                            }
                        }

                        @Override
                        public void error(Throwable t) {
                            try {
                                Map<String, Object> record = new HashMap<>();
                                record.put("eventId", event.getEventId());
                                record.put("functionName", functionName);
                                record.put("errorMessage", t.getMessage());
                                record.put("timestamp", Instant.now().toString());
                                if (streamId != null) {
                                    record.put("streamId", streamId);
                                }
                                storage.failStream(
                                        streamId != null ? streamId : event.getEventId(),
                                        toJson(record)
                                );
                            } catch (Exception e) {
                                log.error("Failed to mark stream error for '{}'", functionName, e);
                            } finally {
                                storage.incrementError();
                            }
                        }
                    };

            streamingFn.handleStream(payload, emitter);
        } catch (Exception ex) {
            log.error("Streaming function '{}' failed", functionName, ex);
            storage.incrementError();
            tryStoreFunctionError(event, functionName, ex);
        } finally {
            // one logical streaming invocation is finished
            decrementActive();
        }
    }

    /**
     * Simple pipeline:
     * payload._chain = ["f1", "f2", "f3"]
     * result of fn1 → input fn2 → input fn3
     */
    @SuppressWarnings("unchecked")
    private void processChain(EventRequest event, Map<String, Object> payload) {
        Object rawChain = payload.get(KEY_CHAIN);
        if (!(rawChain instanceof List<?> chainList) || chainList.isEmpty()) {
            log.warn("CHAIN mode event '{}' has no '{}' list", event.getEventId(), KEY_CHAIN);
            // fallback – просто обычный вызов основной функции
            LocalLambdaFunction fn = functionRegistry.get(event.getFunctionName());
            if (fn == null) {
                storage.incrementError();
                tryStoreGlobalError("Function not found for CHAIN fallback: " + event.getFunctionName(), null);
                decrementActive();
                return;
            }
            processSimple(event, fn, payload, false);
            return;
        }

        Map<String, Object> current = new HashMap<>(payload);
        // remove control fields from input to first function
        current.remove(KEY_MODE);
        current.remove(KEY_CHAIN);

        String pipelineName = event.getFunctionName();

        try {
            for (Object stepObj : chainList) {
                if (!(stepObj instanceof String stepName)) {
                    log.warn("Invalid step name in chain for event '{}': {}", event.getEventId(), stepObj);
                    storage.incrementError();
                    tryStoreGlobalError("Invalid chain step: " + stepObj, null);
                    decrementActive();
                    return;
                }

                LocalLambdaFunction stepFn = functionRegistry.get(stepName);
                if (stepFn == null) {
                    log.warn("Chain step '{}' not found", stepName);
                    storage.incrementError();
                    tryStoreGlobalError("Chain step not found: " + stepName, null);
                    decrementActive();
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
                            decrementActive();
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
            decrementActive();
        }
    }

    // ---------- helpers ----------

    private Map<String, Object> safePayload(EventRequest event) {
        Map<String, Object> payload = event.getPayload();
        return payload != null ? payload : new HashMap<>();
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

    private void decrementActive() {
        storage.decrementActive();
        concurrentInvocations.decrementAndGet();
    }

    /**
     * Simple named thread factory for executors.
     */
    private static class NamedThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger index = new AtomicInteger(0);

        private NamedThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r);
            t.setName(prefix + index.incrementAndGet());
            t.setDaemon(false);
            return t;
        }
    }
}
