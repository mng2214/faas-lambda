package com.faas.worker;

import com.faas.config.LambdaWorkerConfig;
import com.faas.storage.WorkerStorage;
import com.faas.enums.WorkloadType;
import com.faas.dto.EventRequest;
import com.faas.function.LocalLambdaFunction;
import com.faas.registry.FunctionRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class QueueWorker {

    private final WorkerStorage storage;
    private final FunctionRegistry functionRegistry;
    private final LambdaWorkerConfig config;
    private final ObjectMapper objectMapper;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger workerCount = new AtomicInteger(0);
    private final AtomicInteger concurrentInvocations = new AtomicInteger(0);
    private final AtomicInteger cpuIndex = new AtomicInteger(0);

    private ExecutorService workerExecutor;

    // I/O tasks
    private final ExecutorService ioExecutor =
            Executors.newThreadPerTaskExecutor(
                    Thread.ofVirtual().name("faas-io-", 0).factory()
            );

    // CPU tasks
    private final ExecutorService cpuExecutor =
            Executors.newFixedThreadPool(
                    Runtime.getRuntime().availableProcessors(),
                    r -> {
                        Thread t = new Thread(r);
                        t.setName("faas-cpu-" + cpuIndex.incrementAndGet());
                        t.setDaemon(false);
                        return t;
                    }
            );

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
            log.info("Local Lambda worker is disabled via configuration");
            return;
        }

        if (!running.compareAndSet(false, true)) {
            return;
        }

        this.workerExecutor = Executors.newCachedThreadPool(r ->
                Thread.ofVirtual()
                        .name("local-lambda-worker-", 0)
                        .factory()
                        .newThread(r)
        );

        workerExecutor.submit(() -> {
            try {
                Thread.sleep(config.initialDelayMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            log.info("Starting QueueWorker after initialDelay={}ms", config.initialDelayMs());
            startWorker(workerCount.incrementAndGet());
            scheduleAutoscale();
        });
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }

        if (workerExecutor != null) {
            workerExecutor.shutdownNow();
        }

        ioExecutor.shutdownNow();
        cpuExecutor.shutdownNow();

        log.info("QueueWorker stopped");
    }

    /**
     * A single worker is a loop that:
     * - Checks the concurrentInvocations limit
     * - Reads events from the queue
     * - For each event, launches async processing via CPU/IO executor
     */
    private void startWorker(int id) {
        workerExecutor.submit(() -> {
            log.info("Worker-{} started", id);
            while (running.get()) {
                try {

                    if (concurrentInvocations.get() >= config.maxConcurrentInvocations()) {
                        // Brief sleep to prevent CPU spinning in tight loop
                        Thread.sleep(5);
                        continue;
                    }

                    EventRequest event = storage.pollNextEvent(
                            Duration.ofSeconds(config.pollTimeoutSeconds())
                    );

                    if (event == null) {
                        continue;
                    }

                    concurrentInvocations.incrementAndGet();
                    storage.incrementActive();

                    handleEvent(event)
                            .whenComplete((ignored, ex) -> {
                                try {
                                    if (ex != null) {
                                        log.error("Unhandled exception in handleEvent completion", ex);
                                    }
                                    log.info("Successfully handled");
                                } finally {
                                    concurrentInvocations.decrementAndGet();
                                    storage.decrementActive();
                                }
                            });

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.info("Worker-{} interrupted, exiting", id);
                    break;
                } catch (Exception e) {
                    log.error("Unexpected error in worker loop", e);
                }
            }
            log.info("Worker-{} finished", id);
        });
    }

    /**
     * Processes an event asynchronously.
     * Returns a CompletableFuture that completes once the function execution (including retries) finishes.
     */
    private CompletableFuture<Void> handleEvent(EventRequest event) {
        String functionName = event.getFunctionName();
        Map<String, Object> payload = event.getPayload();

        LocalLambdaFunction function = functionRegistry.get(functionName);
        if (function == null) {
            String msg = "Function not found: " + functionName;
            log.warn(msg);
            storage.incrementError();
            tryStoreGlobalError(msg, null);
            return CompletableFuture.completedFuture(null);
        }

        ExecutorService targetExecutor =
                (function.workloadType() == WorkloadType.CPU_BOUND)
                        ? cpuExecutor
                        : ioExecutor;

        int maxAttempts = Math.max(1, config.maxRetries()); // защита от 0/отрицательных

        return CompletableFuture.runAsync(() -> {
            int attempt = 0;

            while (attempt < maxAttempts) {
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
                            functionName, attempt, maxAttempts, ex);

                    if (attempt >= maxAttempts) {
                        storage.incrementError();
                        tryStoreFunctionError(functionName, payload, ex);
                        return;
                    }
                }
            }
        }, targetExecutor);
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

    /**
     * Autoscaling: periodically checks queue length and worker count,
     * adds more workers if the queue is growing.
     */
    private void scheduleAutoscale() {
        workerExecutor.submit(() -> {
            while (running.get()) {
                try {
                    long queueLen = storage.getQueueLength();
                    int desired = calculateDesiredWorkers(queueLen);
                    int current = workerCount.get();

                    if (desired > current) {
                        int toAdd = desired - current;
                        for (int i = 0; i < toAdd; i++) {
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
        int maxWorkers = Math.max(1, config.maxWorkerThreads());

        if (queueLen <= 0) {
            return 1;
        }

        int maxConcurrency = Math.max(1, config.maxConcurrentInvocations());
        long targetPerWorker = Math.max(1L, maxConcurrency / maxWorkers);
        long needed = (queueLen + targetPerWorker - 1) / targetPerWorker;

        if (needed < 1) {
            needed = 1;
        }
        if (needed > maxWorkers) {
            needed = maxWorkers;
        }
        return (int) needed;
    }

}
