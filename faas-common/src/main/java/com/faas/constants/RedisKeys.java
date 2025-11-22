package com.faas.constants;

public class RedisKeys {
    /**
     * Queue of incoming events for the worker.
     */
    public static final String EVENTS_QUEUE = "faas:events";

    /**
     * Global metrics.
     */
    public static final String ACTIVE_INVOCATIONS = "faas:active_invocations";
    public static final String PROCESSED_COUNT = "faas:processed_count";
    public static final String ERROR_COUNT = "faas:error_count";
    public static final String GLOBAL_ERRORS = "faas:errors:global";

    /**
     * Per-function results.
     * Usage: `faas:results:<functionName>`
     */
    public static final String SUCCESS_LIST_PREFIX = "faas:results:";

    /**
     * Per-function error logs.
     * Usage: `faas:errors:<functionName>`
     */
    public static final String FUNCTION_ERRORS_LIST_PREFIX = "faas:errors:";
}
