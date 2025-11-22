package com.faas;

import com.faas.enums.WorkloadType;
import com.faas.model.FunctionEvent;
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

    // сколько воркеров (loop'ов) сейчас запущено
    private final AtomicInteger workerCount = new AtomicInteger(0);
    // сколько функций СЕЙЧАС выполняются
    private final AtomicInteger concurrentInvocations = new AtomicInteger(0);
    private final AtomicInteger cpuIndex = new AtomicInteger(0);

    // виртуальные потоки для воркеров (основной цикл чтения из очереди)
    private ExecutorService workerExecutor;

    // I/O задачи — в виртуальные потоки
    private final ExecutorService ioExecutor =
            Executors.newThreadPerTaskExecutor(
                    Thread.ofVirtual().name("faas-io-", 0).factory()
            );

    // CPU задачи — в реальные потоки
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

        // воркеры тоже запускаем в виртуальных потоках
        this.workerExecutor = Executors.newCachedThreadPool(r ->
                Thread.ofVirtual()
                        .name("local-lambda-worker-", 0)
                        .factory()
                        .newThread(r)
        );

        // стартуем c задержкой (initialDelay), но внутри виртуального потока
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
     * Один "воркер" — это цикл, который:
     *  - смотрит на лимит concurrentInvocations
     *  - читает события из очереди
     *  - на каждое событие запускает async обработку через CPU/IO executor
     */
    private void startWorker(int id) {
        workerExecutor.submit(() -> {
            log.info("Worker-{} started", id);
            while (running.get()) {
                try {
                    // лимит по количеству ОДНОВРЕМЕННО выполняемых функций
                    if (concurrentInvocations.get() >= config.maxConcurrentInvocations()) {
                        // небольшой sleep, чтобы не жрать CPU в спин-лупе
                        Thread.sleep(5);
                        continue;
                    }

                    FunctionEvent event = storage.pollNextEvent(
                            Duration.ofSeconds(config.pollTimeoutSeconds())
                    );

                    if (event == null) {
                        continue;
                    }

                    // считаем активные вызовы и метрики
                    concurrentInvocations.incrementAndGet();
                    storage.incrementActive();

                    // запускаем обработку события асинхронно
                    handleEvent(event)
                            .whenComplete((ignored, ex) -> {
                                try {
                                    if (ex != null) {
                                        log.error("Unhandled exception in handleEvent completion", ex);
                                    }
                                } finally {
                                    // когда функция реально завершилась — уменьшаем счётчики
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
                    // сюда можно добавить tryStoreGlobalError, если надо
                }
            }
            log.info("Worker-{} finished", id);
        });
    }

    /**
     * Асинхронная обработка одного события.
     * Возвращает CompletableFuture, который завершается, когда функция (с ретраями) закончила работу.
     */
    private CompletableFuture<Void> handleEvent(FunctionEvent event) {
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

        // выбираем executor по типу нагрузки
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

                    return; // успех — выходим из цикла и таски
                } catch (Exception ex) {
                    log.error("Error executing function '{}' (attempt {}/{})",
                            functionName, attempt, maxAttempts, ex);

                    if (attempt >= maxAttempts) {
                        storage.incrementError();
                        tryStoreFunctionError(functionName, payload, ex);
                        return;
                    }

                    // если хочешь backoff, можно вставить тут Thread.sleep(...)
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
     * Автоскейл: периодически смотрим на длину очереди и количество воркеров,
     * добавляем воркеров, если очередь растёт.
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
        if (queueLen <= 0) {
            return 1;
        }
        long workers = Math.min(
                config.maxWorkerThreads(),
                Math.max(1, queueLen / 10)
        );
        return (int) workers;
    }
}
