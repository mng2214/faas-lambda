package com.faas.worker;

import com.faas.function.LocalLambdaFunction;
import com.faas.config.LambdaWorkerConfig;
import com.faas.dto.EventRequest;
import com.faas.enums.WorkloadType;
import com.faas.registry.FunctionRegistry;
import com.faas.storage.WorkerStorage;
import com.faas.worker.processor.ChainInvocationProcessor;
import com.faas.worker.processor.NamedThreadFactory;
import com.faas.worker.processor.SimpleInvocationProcessor;
import com.faas.worker.processor.StreamingInvocationProcessor;
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

    // ---- protocol special fields ----
    static final String KEY_MODE = "_mode";              // SIMPLE / STREAM / WEBHOOK / CHAIN

    static final String KEY_CHAIN = "_chain";            // List<String> of function names
    static final String KEY_CALLBACK_URL = "_callbackUrl";
    static final String KEY_STREAM_ID = "_streamId";

    // ---- dependencies ----
    private final WorkerStorage storage;
    private final FunctionRegistry functionRegistry;
    private final LambdaWorkerConfig config;
    private final ObjectMapper objectMapper;

    // ---- worker state ----
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    private final AtomicInteger concurrentInvocations = new AtomicInteger(0);

    private ExecutorService workerExecutor;
    private ExecutorService cpuExecutor;
    private ExecutorService ioExecutor;

    // ---- mode handlers ----
    private final SimpleInvocationProcessor simpleProcessor;
    private final StreamingInvocationProcessor streamingProcessor;
    private final ChainInvocationProcessor chainProcessor;

    public QueueWorker(WorkerStorage storage,
                       FunctionRegistry functionRegistry,
                       LambdaWorkerConfig config,
                       ObjectMapper objectMapper) {
        this.storage = storage;
        this.functionRegistry = functionRegistry;
        this.config = config;
        this.objectMapper = objectMapper;

        // callback that should be called after any invocation completion
        Runnable onInvocationFinished = this::decrementActive;

        this.simpleProcessor =
                new SimpleInvocationProcessor(storage, config, objectMapper, onInvocationFinished);

        this.streamingProcessor =
                new StreamingInvocationProcessor(storage, objectMapper, onInvocationFinished, simpleProcessor);

        this.chainProcessor =
                new ChainInvocationProcessor(storage, functionRegistry, config,
                        objectMapper, simpleProcessor, onInvocationFinished);
    }

    // ========================================================================
    // LIFECYCLE
    // ========================================================================

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

    // ========================================================================
    // MAIN LOOP
    // ========================================================================

    private void startWorkerLoop(int workerIndex) {
        log.info("Worker-{} started", workerIndex);
        Duration pollTimeout = Duration.ofSeconds(Math.max(1, config.pollTimeoutSeconds()));
        int maxConcurrency = Math.max(1, config.maxConcurrentInvocations());

        while (running.get()) {
            try {
                if (concurrentInvocations.get() >= maxConcurrency) {
                    Thread.sleep(5L);
                    continue;
                }

                EventRequest event;
                try {
                    event = storage.pollNextEvent(pollTimeout);
                } catch (IllegalStateException redisEx) {
                    if (isRedisStoppingOrStopped(redisEx)) {
                        log.debug("Worker [{}] Redis is stopping/stopped while polling, exiting loop: {}",
                                workerIndex, redisEx.getMessage());
                        break;
                    }
                    log.error("Worker [{}] failed to poll event", workerIndex, redisEx);
                    tryStoreGlobalError("Worker [%s] failed to poll event %s".formatted(workerIndex, redisEx), redisEx);
                    continue;
                }

                if (event == null) {
                    continue;
                }

                // дальше твоя обычная логика обработки event...

            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                if (shuttingDown.get()) {
                    log.debug("Worker [{}] interrupted due to shutdown, exiting loop", workerIndex);
                    break;
                }
                log.warn("Worker [{}] interrupted unexpectedly: {}", workerIndex, ie.getMessage());
            } catch (IllegalStateException redisEx) {
                if (isRedisStoppingOrStopped(redisEx)) {
                    log.debug("Worker [{}] Redis is stopping/stopped (outer catch), exiting loop: {}",
                            workerIndex, redisEx.getMessage());
                    break;
                }
                log.error("Worker [{}] unexpected Redis error in main loop", workerIndex, redisEx);
                tryStoreGlobalError("Worker [%s] unexpected Redis error in main loop".formatted(workerIndex), redisEx);
            } catch (Exception ex) {
                if (shuttingDown.get()) {
                    log.debug("Worker [{}] caught exception during shutdown: {}", workerIndex, ex.getMessage());
                    break;
                }
                log.error("Worker [{}] unexpected error in main loop, will try to store global error", workerIndex, ex);
                tryStoreGlobalError("Worker [%s] unexpected error in main loop, will try to store global error".formatted(workerIndex), ex);

            }
        }


        log.info("Worker-{} stopped", workerIndex);
    }

    // ========================================================================
    // DISPATCH
    // ========================================================================

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

        // select executor based on workload type
        ExecutorService targetExecutor =
                function.workloadType() == WorkloadType.CPU_BOUND ? cpuExecutor : ioExecutor;

        switch (mode) {
            case "STREAM", "STREAMING" ->
                    targetExecutor.submit(() -> streamingProcessor.processStream(event, function, payload));
            case "CHAIN" -> targetExecutor.submit(() -> chainProcessor.processChain(event, payload));
            case "WEBHOOK" ->
                    targetExecutor.submit(() -> simpleProcessor.processSimple(event, function, payload, true));
            default -> targetExecutor.submit(() -> simpleProcessor.processSimple(event, function, payload, false));
        }
    }

    // ========================================================================
    // SHARED HELPERS
    // ========================================================================

    private boolean isRedisStoppingOrStopped(Exception ex) {
        String msg = ex.getMessage();
        if (msg == null) {
            return false;
        }
        return msg.contains("LettuceConnectionFactory is STOPPING")
                || msg.contains("LettuceConnectionFactory has been STOPPED");
    }


    private Map<String, Object> safePayload(EventRequest event) {
        Map<String, Object> payload = event.getPayload();
        return payload != null ? payload : new HashMap<>();
    }

    int resolveMaxAttempts(LocalLambdaFunction fn) {
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

    String toJson(Object value) throws JsonProcessingException {
        return objectMapper.writeValueAsString(value);
    }

    void markFunctionError(EventRequest event, String functionName, Exception ex) {
        storage.incrementError();
        tryStoreFunctionError(event, functionName, ex);
    }

    void tryStoreFunctionError(EventRequest event, String functionName, Exception ex) {
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

    void tryStoreGlobalError(String message, Exception ex) {
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

    void decrementActive() {
        storage.decrementActive();
        concurrentInvocations.decrementAndGet();
    }

}