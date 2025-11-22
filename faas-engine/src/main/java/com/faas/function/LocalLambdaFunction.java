package com.faas.function;

import com.faas.enums.WorkloadType;

import java.util.Map;

public interface LocalLambdaFunction {


    default WorkloadType workloadType() {
        return WorkloadType.IO_BOUND;
    }

    default int maxRetries() {
        return 3;
    }

    default String description() {
        return "No description provided.";
    }

    default String displayName() {
        return getName();
    }

    String getName();

    Map<String, Object> handle(Map<String, Object> input);
}
