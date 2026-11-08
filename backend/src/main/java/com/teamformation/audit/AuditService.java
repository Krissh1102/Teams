package com.teamformation.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.teamformation.common.Db;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Append-only history of every team change: who, what, when. Called inside the caller's transaction. */
@Service
public class AuditService {
    public record Entry(long id, UUID teamId, UUID actorId, String actorName, String action,
                        UUID targetId, String targetName, String details, Instant at) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public AuditService(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }

    public void record(UUID teamId, UUID actorId, String action, UUID targetId, Map<String, Object> details) {
        String json = null;
        try { if (details != null && !details.isEmpty()) json = mapper.writeValueAsString(details); }
        catch (JsonProcessingException e) { throw new IllegalStateException(e); }
        jdbc.update("INSERT INTO team_audit_logs(team_id, actor_id, action, target_id, details) VALUES (?,?,?,?,?::jsonb)",
                teamId, actorId, action, targetId, json);
    }

    public List<Entry> list(UUID teamId) {
        return jdbc.query("""
                SELECT a.id, a.team_id, a.actor_id, ua.display_name AS actor_name, a.action,
                       a.target_id, ut.display_name AS target_name, a.details::text AS details, a.created_at
                FROM team_audit_logs a
                LEFT JOIN users ua ON ua.id = a.actor_id
                LEFT JOIN users ut ON ut.id = a.target_id
                WHERE a.team_id = ? ORDER BY a.created_at, a.id""",
                (rs, i) -> new Entry(rs.getLong("id"), Db.uuid(rs, "team_id"), Db.uuid(rs, "actor_id"),
                        rs.getString("actor_name"), rs.getString("action"), Db.uuid(rs, "target_id"),
                        rs.getString("target_name"), rs.getString("details"), Db.instant(rs, "created_at")),
                teamId);
    }
}
