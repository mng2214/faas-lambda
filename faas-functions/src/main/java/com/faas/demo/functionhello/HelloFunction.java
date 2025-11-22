package com.faas.demo.functionhello;

import com.faas.LocalLambdaFunction;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

@Component
public class HelloFunction implements LocalLambdaFunction {

    @Override
    public String getName() {
        return "hello";
    }

    @Override
    public Map<String, Object> handle(Map<String, Object> input) {
        String name = (String) input.getOrDefault("name", "world");
        if (name.equalsIgnoreCase("fail")) {
            throw new RuntimeException("Expected Fail Simulation");
        }
//            try {
//                Thread.sleep(1000);
//            } catch (InterruptedException e) {
//                throw new RuntimeException(e);
//            }

        return Map.of(
                "message", "hello " + name + " from local FaaS!",
                "timestamp", Instant.now().toString()
        );
    }
}


