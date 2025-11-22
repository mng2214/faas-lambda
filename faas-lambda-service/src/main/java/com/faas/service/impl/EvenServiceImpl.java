package com.faas.service.impl;

import com.faas.core.LocalLambdaPlatform;
import com.faas.dto.EventResponse;
import com.faas.enums.ApiStatus;
import com.faas.service.EventService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class EvenServiceImpl implements EventService {

    private final LocalLambdaPlatform localLambdaPlatform;

    @Override
    public EventResponse enqueue(String functionName, Map<String, Object> payload) {

        boolean exists = localLambdaPlatform.functionExists(functionName);
        if (!exists) {
            return EventResponse.builder()
                    .status(ApiStatus.FAIL)
                    .eventId(null)
                    .message("Function '" + functionName + "' does not exist")
                    .build();
        }

        String eventId = localLambdaPlatform.enqueueEvent(functionName, payload);

        return EventResponse.builder()
                .status(ApiStatus.ENQUEUED)
                .eventId(eventId)
                .message("Event accepted and queued for processing")
                .build();
    }

}
