package com.teamformation;

import static org.junit.jupiter.api.Assertions.*;

import com.teamformation.recommendation.Scoring;
import com.teamformation.recommendation.Scoring.Candidate;
import com.teamformation.recommendation.Scoring.Team;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Pure unit tests: the scoring is deterministic and needs no database. */
class ScoringTest {
    private final Team team = new Team(Set.of("java"), Set.of("react", "ui"), Set.of("fintech"), 0);

    private Candidate cand(int offset, List<String> skills, List<String> interests) {
        return new Candidate(UUID.randomUUID(), "c", offset, skills, interests);
    }

    @Test
    void weightsSumToOne() {
        assertEquals(1.0, Scoring.W_SKILLS + Scoring.W_TZ + Scoring.W_INTERESTS, 1e-9);
    }

    @Test
    void aPerfectCandidateScoresOneHundred() {
        Scoring.Result r = Scoring.score(team, cand(0, List.of("react", "ui"), List.of("fintech")));
        assertEquals(100.0, r.total(), 0.001);
    }

    @Test
    void timeZoneScoreFallsLinearlyToZeroAtTwelveHours() {
        assertEquals(1.0, Scoring.score(team, cand(0, List.of(), List.of())).timezone(), 1e-9);
        assertEquals(0.5, Scoring.score(team, cand(360, List.of(), List.of())).timezone(), 1e-9);
        assertEquals(0.0, Scoring.score(team, cand(720, List.of(), List.of())).timezone(), 1e-9);
        assertEquals(0.0, Scoring.score(team, cand(840, List.of(), List.of())).timezone(), 1e-9);
    }

    @Test
    void duplicatingTheTeamsSkillsScoresLowerThanComplementingThem() {
        double redundant = Scoring.score(team, cand(0, List.of("java"), List.of())).skills();
        double complementary = Scoring.score(team, cand(0, List.of("react"), List.of())).skills();
        assertTrue(complementary > redundant);
        assertEquals(0.0, redundant, 1e-9);
    }

    @Test
    void withoutStatedNeedsNoveltyAloneDrivesTheSkillScore() {
        Team open = new Team(Set.of("java"), Set.of(), Set.of(), 0);
        assertEquals(1.0, Scoring.score(open, cand(0, List.of("go", "rust"), List.of())).skills(), 1e-9);
        assertEquals(0.5, Scoring.score(open, cand(0, List.of("go", "java"), List.of())).skills(), 1e-9);
    }

    @Test
    void everyResultComesWithHumanReadableReasons() {
        Scoring.Result r = Scoring.score(team, cand(60, List.of("react"), List.of("fintech")));
        assertTrue(r.reasons().stream().anyMatch(s -> s.contains("react")));
        assertTrue(r.reasons().stream().anyMatch(s -> s.contains("time zone")));
        assertTrue(r.reasons().stream().anyMatch(s -> s.contains("fintech")));
    }
}
