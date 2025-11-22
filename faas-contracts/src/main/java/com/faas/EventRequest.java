package com.faas;

import java.util.Map;

public class EventRequest {

    private String eventId;
    private String functionName;
    private Map<String, Object> payload;

    public EventRequest() {}

    public EventRequest(String eventId, String functionName, Map<String, Object> payload) {
        this.eventId = eventId;
        this.functionName = functionName;
        this.payload = payload;
    }

    public String getEventId() { return eventId; }
    public String getFunctionName() { return functionName; }
    public Map<String, Object> getPayload() { return payload; }
}
