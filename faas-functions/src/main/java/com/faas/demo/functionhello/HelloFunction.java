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

//    @Override
//    public WorkloadType workloadType() {
//        return WorkloadType.CPU_BOUND;
//    }

    private final int[] a = new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 0};
    private final int[] b = new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 0};

    long sum = 0;

    @Override
    public Map<String, Object> handle(Map<String, Object> input) {
        String name = (String) input.getOrDefault("name", "world");
        if (name.equalsIgnoreCase("fail")) {
            throw new RuntimeException("Expected Fail Simulation");
        }

        for (int k : a) {
            for (int i : b) {
                sum += k + i;
            }
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


