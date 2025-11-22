package com.faas.storage;

import com.faas.dto.EventRequest;

import java.time.Duration;

/**
 * Queue/storage abstraction.
 * Implementation is platform-specific (Redis, Kafka, etc.).
 * <p>
 * This interface is intentionally minimal and backward-compatible.
 * Implementations that do not care about streaming can ignore the
 * default methods for streams.
 */
public interface WorkerStorage {

    /**
     * Poll next event from the queue with timeout.
     *
     * @param timeout max time to wait
     * @return next EventRequest or {@code null} if timed out
     */
    EventRequest pollNextEvent(Duration timeout) throws Exception;

    /**
     * Total number of pending events in queue.
     */
    long getQueueLength() throws Exception;

    // ---- metrics for current workers ----

    void incrementActive();

    void decrementActive();

    void incrementProcessed();

    void incrementError();

    // ---- result & error storage ----

    /**
     * Store successful result of a function.
     */
    void storeResult(String functionName, String jsonResult);

    /**
     * Store error that happened inside a specific function.
     */
    void storeFunctionError(String functionName, String jsonError);

    /**
     * Store global engine/platform-level error (e.g. polling or infrastructure issues).
     */
    void storeGlobalError(String jsonError);

    // ---- optional streaming support (default no-op) ----

    /**
     * Append chunk of a streaming result.
     * Default no-op to keep backward compatibility.
     */
    default void appendStreamChunk(String streamId, String jsonChunk) {
        // no-op
    }

    /**
     * Mark streaming result as completed.
     * Default no-op.
     */
    default void completeStream(String streamId) {
        // no-op
    }

    /**
     * Mark streaming result as failed.
     * Default no-op.
     */
    default void failStream(String streamId, String jsonError) {
        // no-op
    }
}
