package com.faas;

/**
 * Конфигурация воркера без Spring.
 */
public class LambdaWorkerConfig {

    private final boolean enabled;
    private final long initialDelayMs;
    private final long pollTimeoutSeconds;
    private final int maxRetries;
    private final int maxWorkerThreads;
    private final int maxConcurrentInvocations;

    public LambdaWorkerConfig(boolean enabled,
                              long initialDelayMs,
                              long pollTimeoutSeconds,
                              int maxRetries,
                              int maxWorkerThreads,
                              int maxConcurrentInvocations) {
        this.enabled = enabled;
        this.initialDelayMs = initialDelayMs;
        this.pollTimeoutSeconds = pollTimeoutSeconds;
        this.maxRetries = maxRetries;
        this.maxWorkerThreads = maxWorkerThreads;
        this.maxConcurrentInvocations = maxConcurrentInvocations;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public long getInitialDelayMs() {
        return initialDelayMs;
    }

    public long getPollTimeoutSeconds() {
        return pollTimeoutSeconds;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public int getMaxWorkerThreads() {
        return maxWorkerThreads;
    }

    public int getMaxConcurrentInvocations() {
        return maxConcurrentInvocations;
    }
}
