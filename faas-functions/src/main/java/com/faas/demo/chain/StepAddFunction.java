package com.faas.demo.chain;

import com.faas.LocalLambdaFunction;
import com.faas.enums.WorkloadType;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class StepAddFunction implements LocalLambdaFunction {

    @Override
    public String getName() {
        return "step-add-chain-1";
    }

    @Override
    public WorkloadType workloadType() {
        return WorkloadType.CPU_BOUND;
    }

    @Override
    public String description() {
        return "Chain step: adds field 'value1'.";
    }

    @Override
    public Map<String, Object> handle(Map<String, Object> input) {
        Map<String, Object> out = new HashMap<>(input != null ? input : Map.of());
        out.put("value1", 10);
        return out;
    }
}
/*
{
  "eventId": "99999999-9999-9999-9999-999999999999",
  "functionName": "pipeline-1",
  "payload": {
    "_mode": "CHAIN",
    "_chain": ["step-add", "step-sum"],
    "value2": 32
  }
}
 */
