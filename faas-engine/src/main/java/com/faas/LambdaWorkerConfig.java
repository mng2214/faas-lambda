package com.faas;

public record LambdaWorkerConfig(boolean enabled,
                                 long initialDelayMs,
                                 long pollTimeoutSeconds,
                                 int maxRetries,
                                 int maxWorkerThreads,
                                 int maxConcurrentInvocations) {

}
