package com.teamformation.hackathon;

import com.teamformation.common.ApiException;
import com.teamformation.common.Db;
import com.teamformation.team.TeamDtos.BoardPage;
import com.teamformation.team.TeamDtos.TeamSummary;
import com.teamformation.team.TeamService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/hackathons")
public class HackathonController {
    public record HackathonView(UUID id, String name, int minTeamSize, int maxTeamSize) {}

    private final JdbcTemplate jdbc;
    private final TeamService teams;

    public HackathonController(JdbcTemplate jdbc, TeamService teams) { this.jdbc = jdbc; this.teams = teams; }

    @GetMapping
    public List<HackathonView> list() {
        return jdbc.query("SELECT id, name, min_team_size, max_team_size FROM hackathons WHERE NOT hidden ORDER BY name", this::map);
    }

    @GetMapping("/{id}")
    public HackathonView get(@PathVariable UUID id) {
        List<HackathonView> r = jdbc.query("SELECT id, name, min_team_size, max_team_size FROM hackathons WHERE id = ?", this::map, id);
        if (r.isEmpty()) throw ApiException.notFound("HACKATHON_NOT_FOUND", "Hackathon not found");
        return r.get(0);
    }

    /** The live "looking for teammates" board: teams that still have an open seat. */
    @GetMapping("/{id}/board")
    public BoardPage board(@PathVariable UUID id, @RequestParam(required = false) String q,
                           @RequestParam(required = false) String skill,
                           @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return teams.board(id, q, skill, page, size);
    }

    @GetMapping("/{id}/my-team")
    public ResponseEntity<TeamSummary> myTeam(@AuthenticationPrincipal UUID userId, @PathVariable UUID id) {
        TeamSummary t = teams.myTeam(userId, id);
        return t == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(t);
    }

    private HackathonView map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new HackathonView(Db.uuid(rs, "id"), rs.getString("name"), rs.getInt("min_team_size"), rs.getInt("max_team_size"));
    }
}
