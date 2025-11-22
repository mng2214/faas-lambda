package com.faas;

import lombok.Getter;


public record LambdaWorkerConfig(boolean enabled,
                                 long initialDelayMs,
                                 long pollTimeoutSeconds,
                                 int maxRetries,
                                 int maxWorkerThreads,
                                 int maxConcurrentInvocations) {

}
