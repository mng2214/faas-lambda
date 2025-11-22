package com.faas;

import com.faas.enums.WorkloadType;

import java.util.Map;

public interface LocalLambdaFunction {

    String getName();

    default WorkloadType workloadType() {
        return WorkloadType.IO_BOUND;
    }

    Map<String, Object> handle (Map<String, Object> input);
}
