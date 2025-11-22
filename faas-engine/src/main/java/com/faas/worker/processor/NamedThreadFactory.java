package com.faas.worker.processor;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simple named thread factory for executors.
 */
public class NamedThreadFactory implements ThreadFactory {
    private final String prefix;
    private final AtomicInteger index = new AtomicInteger(0);

    public NamedThreadFactory(String prefix) {
        this.prefix = prefix;
    }

    @Override
    public Thread newThread(Runnable r) {
        Thread t = new Thread(r);
        t.setName(prefix + index.incrementAndGet());
        t.setDaemon(false);
        return t;
    }
}
