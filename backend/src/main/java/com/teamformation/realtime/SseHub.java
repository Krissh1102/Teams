package com.teamformation.realtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Server-Sent Events hub. Events are *notifications* ({type, teamId}); clients refetch over the
 * authenticated REST API, so the stream itself needs no auth and leaks no data.
 */
@Component
public class SseHub {
    private final Map<String, Set<SseEmitter>> topics = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;

    public SseHub(ObjectMapper mapper) { this.mapper = mapper; }

    public SseEmitter subscribe(String topic) {
        SseEmitter emitter = new SseEmitter(30 * 60_000L);   // browsers auto-reconnect after timeout
        Set<SseEmitter> set = topics.computeIfAbsent(topic, k -> ConcurrentHashMap.newKeySet());
        set.add(emitter);
        Runnable cleanup = () -> set.remove(emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(t -> cleanup.run());
        try { emitter.send(SseEmitter.event().name("ready").data("{}")); }
        catch (IOException | RuntimeException e) { cleanup.run(); }
        return emitter;
    }

    public void publish(String topic, Object payload) {
        Set<SseEmitter> set = topics.get(topic);
        if (set == null || set.isEmpty()) return;
        String json;
        try { json = mapper.writeValueAsString(payload); } catch (JsonProcessingException e) { return; }
        for (SseEmitter e : set) {
            try { e.send(SseEmitter.event().name("change").data(json)); }
            catch (IOException | RuntimeException ex) { set.remove(e); }
        }
    }

    @Scheduled(fixedRate = 20_000)
    void heartbeat() {
        topics.values().forEach(set -> {
            for (SseEmitter e : set) {
                try { e.send(SseEmitter.event().comment("ping")); }
                catch (IOException | RuntimeException ex) { set.remove(e); }
            }
        });
    }
}
