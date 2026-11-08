package com.teamformation.realtime;

import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/stream")
public class StreamController {
    private final SseHub hub;
    public StreamController(SseHub hub) { this.hub = hub; }

    @GetMapping(value = "/hackathons/{id}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter board(@PathVariable UUID id) { return hub.subscribe("hackathon:" + id); }

    @GetMapping(value = "/teams/{id}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter team(@PathVariable UUID id) { return hub.subscribe("team:" + id); }
}
