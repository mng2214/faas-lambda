package com.faas.api;

import com.faas.dto.EventResponse;
import com.faas.service.EventService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/events")
public class EventController {

    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    @PostMapping("/{functionName}")
    public ResponseEntity<EventResponse> enqueue(@PathVariable String functionName,
                                                 @RequestBody Map<String, Object> payload) {

        EventResponse response = eventService.enqueue(functionName, payload);

        return ResponseEntity
                .status(response.getStatus().code())
                .body(response);
    }
}
