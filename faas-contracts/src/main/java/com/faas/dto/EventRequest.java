package com.faas.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EventRequest {

    private String eventId;
    private String functionName;
    private Map<String, Object> payload;

}
