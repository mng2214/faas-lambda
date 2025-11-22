package com.faas.worker;

import com.faas.config.LambdaWorkerConfig;
import com.faas.dto.EventRequest;
import com.faas.enums.WorkloadType;
import com.faas.function.LocalLambdaFunction;
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

    // ---- спец-поля протокола ----
    static final String KEY_MODE = "_mode";              // SIMPLE / STREAM / WEBHOOK / CHAIN

    static final String KEY_CHAIN = "_chain";            // List<String> of function names
    static final String KEY_CALLBACK_URL = "_callbackUrl";
    static final String KEY_STREAM_ID = "_streamId";

    // ---- зависимости ----
    private final WorkerStorage storage;
    private final FunctionRegistry functionRegistry;
    private final LambdaWorkerConfig config;
    private final ObjectMapper objectMapper;

    // ---- состояние воркера ----
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger concurrentInvocations = new AtomicInteger(0);

    private ExecutorService workerExecutor;
    private ExecutorService cpuExecutor;
    private ExecutorService ioExecutor;

    // ---- обработчики режимов ----
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

        // callback, который должен вызываться после любого завершения инвокации
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

    private void startWorkerLoop(int id) {
        log.info("Worker-{} started", id);
        Duration pollTimeout = Duration.ofSeconds(Math.max(1, config.pollTimeoutSeconds()));
        int maxConcurrency = Math.max(1, config.maxConcurrentInvocations());

        while (running.get()) {
            try {
                // простая back-pressure по concurrentInvocations
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
                // глобальная ошибка storage
                tryStoreGlobalError("Failed to poll event", ex);
            }
        }

        log.info("Worker-{} stopped", id);
    }

    // ========================================================================
    // DISPATCH
    // ========================================================================

    private void dispatchEvent(EventRequest event) {
        // определяем функцию
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

        // выбираем executor по типу нагрузки
        ExecutorService targetExecutor =
                function.workloadType() == WorkloadType.CPU_BOUND ? cpuExecutor : ioExecutor;

        switch (mode) {
            case "STREAM", "STREAMING" ->
                    targetExecutor.submit(() -> streamingProcessor.processStream(event, function, payload));
            case "CHAIN" ->
                    targetExecutor.submit(() -> chainProcessor.processChain(event, payload));
            case "WEBHOOK" ->
                    targetExecutor.submit(() -> simpleProcessor.processSimple(event, function, payload, true));
            default ->
                    targetExecutor.submit(() -> simpleProcessor.processSimple(event, function, payload, false));
        }
    }

    // ========================================================================
    // SHARED HELPERS
    // ========================================================================

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
