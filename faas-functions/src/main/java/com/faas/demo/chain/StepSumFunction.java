package com.faas.demo.chain;

import com.faas.LocalLambdaFunction;
import com.faas.enums.WorkloadType;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class StepSumFunction implements LocalLambdaFunction {

    @Override
    public String getName() {
        return "step-sum-chain-2";
    }

    @Override
    public WorkloadType workloadType() {
        return WorkloadType.CPU_BOUND;
    }

    @Override
    public String description() {
        return "Chain step: sums 'value1' and 'value2'.";
    }

    @Override
    public Map<String, Object> handle(Map<String, Object> input) {
        Map<String, Object> out = new HashMap<>(input != null ? input : Map.of());
        int v1 = ((Number) out.getOrDefault("value1", 0)).intValue();
        int v2 = ((Number) out.getOrDefault("value2", 0)).intValue();
        out.put("sum", v1 + v2);
        return out;
    }
}
