package com.faas.dto;

import com.faas.enums.WorkloadType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FunctionInfo {
    private String name;
    private String displayName;
    private String description;
    private WorkloadType workloadType;
    private int maxRetries;
}