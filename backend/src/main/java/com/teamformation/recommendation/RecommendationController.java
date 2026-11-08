package com.teamformation.recommendation;

import com.teamformation.recommendation.RecommendationService.SuggestionResponse;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/teams")
public class RecommendationController {
    private final RecommendationService service;
    public RecommendationController(RecommendationService service) { this.service = service; }

    /** Ranked, explained teammate suggestions for a team (members only). */
    @GetMapping("/{id}/suggestions")
    public SuggestionResponse suggestions(@AuthenticationPrincipal UUID userId, @PathVariable UUID id,
                                          @RequestParam(defaultValue = "10") int limit) {
        return service.suggest(userId, id, limit);
    }
}
