package com.faas.demo;

import com.faas.function.LocalLambdaFunction;
import com.faas.enums.WorkloadType;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Component
public class HelloFunction implements LocalLambdaFunction {

    @Override
    public String getName() {
        return "hello";
    }

    @Override
    public WorkloadType workloadType() {
        return WorkloadType.IO_BOUND;
    }

    @Override
    public String description() {
        return "Simple hello function for testing SIMPLE mode.";
    }

    @Override
    public Map<String, Object> handle(Map<String, Object> input) {
        Map<String, Object> out = new HashMap<>();
        String name = input != null && input.get("name") != null
                ? String.valueOf(input.get("name"))
                : "world";

        out.put("message", "hello " + name + " from local FaaS!");
        out.put("timestamp", Instant.now().toString());
        out.put("input", input);
        return out;
    }
}
/*
{
  "eventId": "11111111-2222-3333-4444-555555555555",
  "functionName": "hello",
  "payload": {
    "name": "Artur"
  }
}
 */