package com.faas;

import com.faas.model.FunctionEvent;

import java.time.Duration;

/**
 * Абстракция очереди/хранилища.
 * Реализация — в платформе (Redis, Kafka и т.д.).
 */
public interface WorkerStorage {

    /**
     * Забрать следующее событие из очереди.
     *
     * @param timeout время ожидания
     * @return событие или null, если за timeout ничего не пришло
     */
    FunctionEvent pollNextEvent(Duration timeout) throws Exception;

    long getQueueLength() throws Exception;

    // Метрики
    void incrementActive();
    void decrementActive();
    void incrementProcessed();
    void incrementError();

    long getActiveCount();
    long getProcessedCount();
    long getErrorCount();

    /**
     * Успешный результат функции (JSON уже подготовлен воркером).
     */
    void storeResult(String functionName, String jsonResult);

    /**
     * Ошибка конкретной функции.
     */
    void storeFunctionError(String functionName, String jsonError);

    /**
     * Глобальная ошибка.
     */
    void storeGlobalError(String jsonError);
}
