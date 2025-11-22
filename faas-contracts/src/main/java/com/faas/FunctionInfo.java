package com.faas;

import com.faas.enums.WorkloadType;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class FunctionInfo {
    private String name;
    private String displayName;
    private String description;
    private WorkloadType workloadType;
    private int maxRetries;
}
