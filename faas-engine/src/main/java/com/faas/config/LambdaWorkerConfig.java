package com.faas.config;

public record LambdaWorkerConfig(boolean enabled,
                                 long initialDelayMs,
                                 long pollTimeoutSeconds,
                                 int maxRetries,
                                 int maxWorkerThreads,
                                 int maxConcurrentInvocations) {

}
