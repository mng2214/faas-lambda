package com.faas.service;

import com.faas.dto.EventResponse;

import java.util.Map;

public interface EventService {

    EventResponse enqueue(String functionName, Map<String, Object> payload);

}
