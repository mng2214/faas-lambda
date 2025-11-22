package com.faas.dto;

import com.faas.enums.ApiStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventResponse {
    private ApiStatus status;
    private String eventId;
    private String message;
}
