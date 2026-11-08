package com.teamformation.arena;

import com.teamformation.arena.ArenaService.Mode;
import com.teamformation.arena.ArenaService.Report;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Demo-only endpoints; the beans do not exist (404) unless ARENA_ENABLED=true. Login required. */
@RestController
@RequestMapping("/api/arena")
@ConditionalOnProperty(name = "app.arena.enabled", havingValue = "true")
public class ArenaController {
    public record RaceRequest(@NotNull Mode mode, @Min(2) @Max(100) Integer contenders, @Min(2) @Max(10) Integer capacity) {}

    private final ArenaService service;
    public ArenaController(ArenaService service) { this.service = service; }

    @GetMapping("/status")
    public Map<String, Object> status() { return Map.of("enabled", true); }

    @PostMapping("/race")
    public Report race(@Valid @RequestBody RaceRequest r) {
        return service.run(r.mode(), r.contenders() == null ? 50 : r.contenders(), r.capacity() == null ? 5 : r.capacity());
    }
}
