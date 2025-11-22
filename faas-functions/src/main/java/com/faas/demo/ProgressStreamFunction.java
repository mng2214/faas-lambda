package com.faas.demo;

import com.faas.enums.WorkloadType;
import com.faas.function.LocalLambdaFunction;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Component
public class ProgressStreamFunction implements LocalLambdaFunction.Streaming {

    @Override
    public String getName() {
        return "progress-stream";
    }

    @Override
    public WorkloadType workloadType() {
        return WorkloadType.CPU_BOUND;
    }

    @Override
    public String description() {
        return "Emits 5 progress chunks for STREAM mode testing.";
    }

    @Override
    public Map<String, Object> handle(Map<String, Object> input) {
        Map<String, Object> out = new HashMap<>();
        out.put("message", "This is default SIMPLE response from progress-stream");
        out.put("timestamp", Instant.now().toString());
        return out;
    }

    @Override
    public void handleStream(Map<String, Object> input, StreamEmitter emitter) {
        try {
            int steps = 5;
            for (int i = 1; i <= steps; i++) {
                Map<String, Object> chunk = new HashMap<>();
                chunk.put("step", i);
                chunk.put("totalSteps", steps);
                chunk.put("progress", (i * 100) / steps);
                chunk.put("timestamp", Instant.now().toString());
                chunk.put("input", input);

                emitter.next(chunk);

                // имитация работы
                Thread.sleep(300);
            }
            emitter.complete();
        } catch (Exception e) {
            emitter.error(e);
        }
    }
}
/*
{
  "eventId": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
  "functionName": "progress-stream",
  "payload": {
    "_mode": "STREAM",
    "_streamId": "stream-123",
    "jobId": "job-42"
  }
}
 */