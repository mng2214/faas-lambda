package com.faas;

import lombok.Getter;

@Getter
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

}
