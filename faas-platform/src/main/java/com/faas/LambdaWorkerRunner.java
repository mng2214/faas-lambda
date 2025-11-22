package com.faas;


import com.faas.worker.QueueWorker;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class LambdaWorkerRunner {

    private static final Logger log = LoggerFactory.getLogger(LambdaWorkerRunner.class);

    private final QueueWorker worker;

    public LambdaWorkerRunner(QueueWorker worker) {
        this.worker = worker;
    }

    @PostConstruct
    public void start() {
        log.info("Starting LambdaWorkerRunner (platform)...");
        worker.start();
    }

    @PreDestroy
    public void stop() {
        log.info("Stopping LambdaWorkerRunner (platform)...");
        worker.stop();
    }
}
