package com.faas;

import com.faas.dto.EventRequest;

import java.time.Duration;

/**
 * Queue/storage abstraction.
 * Implementation is platform-specific (Redis, Kafka, etc.).
 */
public interface WorkerStorage {

    EventRequest pollNextEvent(Duration timeout) throws Exception;

    long getQueueLength() throws Exception;

    void incrementActive();

    void decrementActive();

    void incrementProcessed();

    void incrementError();

    long getActiveCount();

    long getProcessedCount();

    long getErrorCount();

    void storeResult(String functionName, String jsonResult);

    void storeFunctionError(String functionName, String jsonError);

    void storeGlobalError(String jsonError);
}
