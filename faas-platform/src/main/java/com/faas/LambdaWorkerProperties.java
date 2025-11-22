package com.faas;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Конфиг для воркера, подтягивается из application.yml.
 */
@Getter
@Component
@ConfigurationProperties(prefix = "app") // можешь поменять на "faas"
public class LambdaWorkerProperties {

    private final Worker worker = new Worker();
    private final Lambda lambda = new Lambda();

    @Setter
    @Getter
    public static class Worker {
        private boolean enabled = true;
        private long initialDelay = 5_000L;
        private long timeoutSeconds = 30L;

    }

    @Setter
    @Getter
    public static class Lambda {
        private int memoryMb = 512;
        private int maxRetries = 3;
        private int maxConcurrent = 256;
        private int maxWorkerThreads = 4;
    }
}
