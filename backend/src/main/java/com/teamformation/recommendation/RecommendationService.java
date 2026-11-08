package com.teamformation.recommendation;

import com.teamformation.common.ApiException;
import com.teamformation.common.Db;
import com.teamformation.recommendation.Scoring.Candidate;
import com.teamformation.team.TeamService;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecommendationService {
    public record Suggestion(UUID userId, String displayName, List<String> skills, int utcOffsetMin,
                             double score, Map<String, Double> breakdown, List<String> reasons) {}

    public record SuggestionResponse(Map<String, Double> weights, String explanation, List<Suggestion> suggestions) {}

    private static final String EXPLANATION = "score = 100 x (0.5 x skills + 0.3 x timezone + 0.2 x interests). "
            + "skills rewards candidates who cover what your team is looking for (60%) and bring skills the team lacks (40%); "
            + "timezone falls linearly to 0 at 12 hours from your team's average offset; "
            + "interests is the Jaccard overlap with your members' interests.";

    private final JdbcTemplate jdbc;
    private final TeamService teams;

    public RecommendationService(JdbcTemplate jdbc, TeamService teams) { this.jdbc = jdbc; this.teams = teams; }

    @Transactional(readOnly = true)
    public SuggestionResponse suggest(UUID userId, UUID teamId, int limit) {
        teams.requireMember(teamId, userId);
        List<Object[]> t = jdbc.query("SELECT hackathon_id, looking_for FROM teams WHERE id = ?",
                (rs, i) -> new Object[]{Db.uuid(rs, "hackathon_id"), Db.strings(rs, "looking_for")}, teamId);
        if (t.isEmpty()) throw ApiException.notFound("TEAM_NOT_FOUND", "Team not found");
        UUID hackathonId = (UUID) t.get(0)[0];
        @SuppressWarnings("unchecked") List<String> lookingFor = (List<String>) t.get(0)[1];

        // team context from its current members
        Set<String> skills = new LinkedHashSet<>(), interests = new LinkedHashSet<>();
        double[] offsetSum = {0}; int[] n = {0};
        jdbc.query("""
                SELECT u.utc_offset_min, u.interests,
                       COALESCE((SELECT array_agg(s.skill) FROM user_skills s WHERE s.user_id = u.id), '{}') AS skills
                FROM team_members m JOIN users u ON u.id = m.user_id WHERE m.team_id = ?""",
                rs -> {
                    offsetSum[0] += rs.getInt("utc_offset_min"); n[0]++;
                    skills.addAll(Db.strings(rs, "skills")); interests.addAll(Db.strings(rs, "interests"));
                }, teamId);
        Scoring.Team team = new Scoring.Team(skills, new LinkedHashSet<>(lookingFor), interests, n[0] == 0 ? 0 : offsetSum[0] / n[0]);

        // candidates: everyone in this hackathon who is not on a team yet (capped, so the endpoint stays cheap)
        List<Candidate> candidates = jdbc.query("""
                SELECT u.id, u.display_name, u.utc_offset_min, u.interests,
                       COALESCE((SELECT array_agg(s.skill ORDER BY s.skill) FROM user_skills s WHERE s.user_id = u.id), '{}') AS skills
                FROM users u
                WHERE NOT EXISTS (SELECT 1 FROM team_members m WHERE m.user_id = u.id AND m.hackathon_id = ?)
                ORDER BY u.id LIMIT 5000""",
                (rs, i) -> new Candidate(Db.uuid(rs, "id"), rs.getString("display_name"), rs.getInt("utc_offset_min"),
                        Db.strings(rs, "skills"), Db.strings(rs, "interests")), hackathonId);

        List<Suggestion> ranked = candidates.stream().map(c -> {
                    Scoring.Result r = Scoring.score(team, c);
                    return new Suggestion(c.id(), c.name(), c.skills(), c.utcOffsetMin(), r.total(),
                            Map.of("skills", r.skills(), "timezone", r.timezone(), "interests", r.interests()), r.reasons());
                })
                .sorted(Comparator.comparingDouble(Suggestion::score).reversed().thenComparing(Suggestion::userId))
                .limit(Math.min(Math.max(limit, 1), 50)).toList();

        return new SuggestionResponse(Map.of("skills", Scoring.W_SKILLS, "timezone", Scoring.W_TZ, "interests", Scoring.W_INTERESTS),
                EXPLANATION, ranked);
    }
}
