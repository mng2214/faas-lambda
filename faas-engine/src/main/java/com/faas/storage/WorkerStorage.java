package com.faas.storage;

import com.faas.dto.EventRequest;

import java.time.Duration;
import java.util.List;

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

    void storeResult(String functionName, String jsonResult);

    void storeFunctionError(String functionName, String jsonError);

    void storeGlobalError(String jsonError);
}
