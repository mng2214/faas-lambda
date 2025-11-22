package com.faas;

import com.faas.model.FunctionEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Чистый воркер движка: ничего не знает про Spring/Redis.
 * Работает через WorkerStorage + FunctionRegistry.
 */
public class QueueWorker {

    private static final Logger log = LoggerFactory.getLogger(QueueWorker.class);

    private final WorkerStorage storage;
    private final FunctionRegistry functionRegistry;
    private final LambdaWorkerConfig config;
    private final ObjectMapper objectMapper;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger workerCount = new AtomicInteger(0);
    private final AtomicInteger concurrentInvocations = new AtomicInteger(0);

    private ExecutorService executor;

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
        if (!config.isEnabled()) {
            log.info("Local Lambda worker is disabled via configuration");
            return;
        }

        if (!running.compareAndSet(false, true)) {
            return;
        }

        this.executor = Executors.newCachedThreadPool(r ->
                Thread.ofVirtual().name("local-lambda-worker-", 0).factory().newThread(r));

        // отложенный старт
        CompletableFuture
                .delayedExecutor(config.getInitialDelayMs(), TimeUnit.MILLISECONDS)
                .execute(() -> {
                    log.info("Starting QueueWorker after initialDelay={}ms", config.getInitialDelayMs());
                    startWorker(workerCount.incrementAndGet());
                    scheduleAutoscale();
                });
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }

        if (executor != null) {
            executor.shutdownNow();
        }

        log.info("QueueWorker stopped");
    }

    private void startWorker(int id) {
        executor.submit(() -> {
            log.info("Worker-{} started", id);
            while (running.get()) {
                try {
                    if (concurrentInvocations.get() >= config.getMaxConcurrentInvocations()) {
                        Thread.sleep(10);
                        continue;
                    }

                    FunctionEvent event = storage.pollNextEvent(
                            Duration.ofSeconds(config.getPollTimeoutSeconds()));

                    if (event == null) {
                        continue;
                    }

                    concurrentInvocations.incrementAndGet();
                    storage.incrementActive();

                    handleEvent(event);

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.info("Worker-{} interrupted, exiting", id);
                    break;
                } catch (Exception e) {
                    log.error("Unexpected error in worker loop", e);
                    tryStoreGlobalError("Unexpected worker error: " + e.getMessage(), e);
                } finally {
                    concurrentInvocations.decrementAndGet();
                    storage.decrementActive();
                }
            }
            log.info("Worker-{} finished", id);
        });
    }

    private void handleEvent(FunctionEvent event) {
        String functionName = event.getFunctionName();
        Map<String, Object> payload = event.getPayload();

        LocalLambdaFunction function = functionRegistry.get(functionName);
        if (function == null) {
            String msg = "Function not found: " + functionName;
            log.warn(msg);
            tryStoreGlobalError(msg, null);
            storage.incrementError();
            return;
        }

        int attempt = 0;
        while (attempt <= config.getMaxRetries()) {
            attempt++;
            try {
                Map<String, Object> output = function.handle(payload);

                storage.incrementProcessed();

                Map<String, Object> record = new HashMap<>();
                record.put("eventId", event.getEventId());
                record.put("functionName", functionName);
                record.put("output", output);
                record.put("status", "success");
                record.put("timestamp", Instant.now().toString());

                String json = objectMapper.writeValueAsString(record);
                storage.storeResult(functionName, json);

                return;
            } catch (Exception ex) {
                log.error("Error executing function '{}' (attempt {}/{})",
                        functionName, attempt, config.getMaxRetries() + 1, ex);

                if (attempt > config.getMaxRetries()) {
                    storage.incrementError();
                    tryStoreFunctionError(functionName, payload, ex);
                    return;
                }
            }
        }
    }

    private void tryStoreFunctionError(String functionName,
                                       Map<String, Object> payload,
                                       Throwable error) {
        try {
            Map<String, Object> record = new HashMap<>();
            record.put("functionName", functionName);
            record.put("payload", payload);
            record.put("status", "error");
            record.put("timestamp", Instant.now().toString());
            record.put("errorMessage", error != null ? error.getMessage() : null);

            String json = objectMapper.writeValueAsString(record);
            storage.storeFunctionError(functionName, json);
        } catch (Exception e) {
            log.error("Failed to serialize function error: {}", e.getMessage(), e);
        }
    }

    private void tryStoreGlobalError(String message, Throwable error) {
        try {
            Map<String, Object> record = new HashMap<>();
            record.put("status", "error");
            record.put("timestamp", Instant.now().toString());
            record.put("message", message);
            if (error != null) {
                record.put("errorMessage", error.getMessage());
            }

            String json = objectMapper.writeValueAsString(record);
            storage.storeGlobalError(json);
        } catch (Exception e) {
            log.error("Failed to serialize global error: {}", e.getMessage(), e);
        }
    }

    private void scheduleAutoscale() {
        executor.submit(() -> {
            while (running.get()) {
                try {
                    long queueLen = storage.getQueueLength();
                    int desired = calculateDesiredWorkers(queueLen);
                    int current = workerCount.get();

                    if (desired > current) {
                        int toStart = Math.min(desired - current,
                                config.getMaxWorkerThreads() - current);

                        for (int i = 0; i < toStart; i++) {
                            int id = workerCount.incrementAndGet();
                            startWorker(id);
                        }

                        log.info("Scaled workers: current={}, desired={}, queueLen={}",
                                workerCount.get(), desired, queueLen);
                    }

                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("Error in autoscale loop", e);
                }
            }
        });
    }

    private int calculateDesiredWorkers(long queueLen) {
        if (queueLen <= 0) {
            return 1;
        }
        long workers = Math.min(config.getMaxWorkerThreads(), Math.max(1, queueLen / 10));
        return (int) workers;
    }
}
