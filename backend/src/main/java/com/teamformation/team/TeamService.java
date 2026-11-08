package com.teamformation.team;

import com.teamformation.audit.AuditService;
import com.teamformation.common.ApiException;
import com.teamformation.common.Db;
import com.teamformation.config.AppProperties;
import com.teamformation.realtime.TeamChanged;
import com.teamformation.team.TeamDtos.*;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * All membership changes live here. THE RULE: every operation that changes a team's membership
 * first takes the team row lock (SELECT ... FOR UPDATE) and nothing else is locked before it.
 * One lock order => no deadlocks, and capacity checks run against fresh, serialized state.
 * The UNIQUE(user_id, hackathon_id) index and CHECK(member_count <= max_size) are the safety nets
 * that also cover a user racing across two different teams.
 *
 * Plain SQL (JdbcTemplate) is used on purpose: the locking statements are explicit and reviewable.
 */
@Service
public class TeamService {
    private static final SecureRandom RANDOM = new SecureRandom();

    private record LockedTeam(UUID id, UUID hackathonId, UUID leaderId, int maxSize, int memberCount) {}

    private static final String SUMMARY_SELECT = """
            SELECT t.id, t.hackathon_id, t.name, t.description, t.looking_for, t.max_size, t.member_count,
                   t.status, t.leader_id, u.display_name AS leader_name, t.created_at
            FROM teams t JOIN users u ON u.id = t.leader_id
            """;

    static final RowMapper<TeamSummary> SUMMARY = (rs, i) -> new TeamSummary(
            Db.uuid(rs, "id"), Db.uuid(rs, "hackathon_id"), rs.getString("name"), rs.getString("description"),
            Db.strings(rs, "looking_for"), rs.getInt("max_size"), rs.getInt("member_count"), rs.getString("status"),
            Db.uuid(rs, "leader_id"), rs.getString("leader_name"), Db.instant(rs, "created_at"));

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final AppProperties props;

    public TeamService(JdbcTemplate jdbc, AuditService audit, ApplicationEventPublisher events, AppProperties props) {
        this.jdbc = jdbc; this.audit = audit; this.events = events; this.props = props;
    }

    // ------------------------------------------------------------------ create / read / update

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TeamDetail create(UUID userId, CreateTeamRequest req) {
        List<int[]> h = jdbc.query("SELECT min_team_size, max_team_size FROM hackathons WHERE id = ?",
                (rs, i) -> new int[]{rs.getInt(1), rs.getInt(2)}, req.hackathonId());
        if (h.isEmpty()) throw ApiException.notFound("HACKATHON_NOT_FOUND", "Hackathon not found");
        int min = h.get(0)[0], hackMax = h.get(0)[1];
        int max = req.maxSize() == null ? hackMax : req.maxSize();
        if (max < min || max > hackMax)
            throw ApiException.badRequest("INVALID_TEAM_SIZE", "maxSize must be between " + min + " and " + hackMax);
        if (isInTeam(userId, req.hackathonId()))
            throw ApiException.conflict("ALREADY_IN_TEAM", "You are already in a team for this hackathon");

        UUID id = UUID.randomUUID();
        // uq_user_hackathon below catches two simultaneous creates by the same user.
        jdbc.update("INSERT INTO teams(id, hackathon_id, leader_id, name, description, looking_for, max_size, member_count, status) "
                        + "VALUES (?,?,?,?,?,?,?,1,?)",
                id, req.hackathonId(), userId, req.name().trim(),
                req.description() == null ? null : req.description().trim(),
                Db.textArray(Db.normalize(req.lookingFor(), 10)), max, 1 >= min ? "READY" : "FORMING");
        jdbc.update("INSERT INTO team_members(team_id, user_id, hackathon_id) VALUES (?,?,?)", id, userId, req.hackathonId());
        audit.record(id, userId, "TEAM_CREATED", null, Map.of("name", req.name().trim(), "maxSize", max));
        events.publishEvent(new TeamChanged(id, req.hackathonId(), "TEAM_CREATED"));
        return get(id);
    }

    @Transactional(readOnly = true)
    public TeamDetail get(UUID teamId) {
        List<TeamSummary> s = jdbc.query(SUMMARY_SELECT + "WHERE t.id = ?", SUMMARY, teamId);
        if (s.isEmpty()) throw ApiException.notFound("TEAM_NOT_FOUND", "Team not found");
        List<MemberView> members = jdbc.query("""
                SELECT m.user_id, u.display_name, m.joined_at, (t.leader_id = m.user_id) AS leader,
                       COALESCE((SELECT array_agg(s.skill ORDER BY s.skill) FROM user_skills s WHERE s.user_id = u.id), '{}') AS skills
                FROM team_members m JOIN users u ON u.id = m.user_id JOIN teams t ON t.id = m.team_id
                WHERE m.team_id = ? ORDER BY m.joined_at, m.user_id""",
                (rs, i) -> new MemberView(Db.uuid(rs, "user_id"), rs.getString("display_name"), Db.strings(rs, "skills"),
                        Db.instant(rs, "joined_at"), rs.getBoolean("leader")), teamId);
        return new TeamDetail(s.get(0), members);
    }

    @Transactional
    public TeamDetail update(UUID userId, UUID teamId, UpdateTeamRequest req) {
        LockedTeam t = lock(teamId);
        requireLeader(t, userId);
        if (req.name() != null) jdbc.update("UPDATE teams SET name = ? WHERE id = ?", req.name().trim(), teamId);
        if (req.description() != null) jdbc.update("UPDATE teams SET description = ? WHERE id = ?", req.description().trim(), teamId);
        if (req.lookingFor() != null)
            jdbc.update("UPDATE teams SET looking_for = ? WHERE id = ?", Db.textArray(Db.normalize(req.lookingFor(), 10)), teamId);
        audit.record(teamId, userId, "TEAM_UPDATED", null, null);
        events.publishEvent(new TeamChanged(teamId, t.hackathonId(), "TEAM_UPDATED"));
        return get(teamId);
    }

    // ------------------------------------------------------------------ invites + joining (the race-critical path)

    @Transactional
    public InviteView createInvite(UUID userId, UUID teamId) {
        LockedTeam t = lock(teamId);
        requireLeader(t, userId);
        byte[] raw = new byte[16];                       // 128 bits of CSPRNG entropy
        RANDOM.nextBytes(raw);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Instant expires = Instant.now().plus(props.inviteTtlHours(), ChronoUnit.HOURS);
        jdbc.update("UPDATE team_invites SET revoked = TRUE WHERE team_id = ? AND NOT revoked", teamId);   // one live code per team
        jdbc.update("INSERT INTO team_invites(team_id, code_hash, expires_at, created_by) VALUES (?,?,?,?)",
                teamId, Db.sha256Hex(code), java.sql.Timestamp.from(expires), userId);
        audit.record(teamId, userId, "INVITE_CREATED", null, Map.of("expiresAt", expires.toString()));
        return new InviteView(code, expires);            // the only time the raw code exists
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TeamDetail joinWithInvite(UUID userId, UUID teamId, String code) {
        // 1. cheap read-only invite check first, so bad codes never queue on the team lock
        Integer valid = jdbc.queryForObject(
                "SELECT count(*) FROM team_invites WHERE team_id = ? AND code_hash = ? AND NOT revoked AND expires_at > now()",
                Integer.class, teamId, Db.sha256Hex(code));
        if (valid == null || valid == 0) throw ApiException.badRequest("INVALID_INVITE", "Invite code is invalid or has expired");

        // 2. serialize everyone contending for THIS team: the first transaction in takes the last seat,
        //    the rest wake up, re-read the fresh row (READ COMMITTED) and see the team is full.
        LockedTeam t = lock(teamId);
        addMember(t, userId);
        audit.record(teamId, userId, "MEMBER_JOINED", userId, Map.of("via", "invite"));
        events.publishEvent(new TeamChanged(teamId, t.hackathonId(), "MEMBER_JOINED"));
        return get(teamId);
    }

    /** Capacity + membership checks and the insert. Caller MUST already hold the team lock. */
    private void addMember(LockedTeam t, UUID userId) {
        if (isInTeam(userId, t.hackathonId()))
            throw ApiException.conflict("ALREADY_IN_TEAM", "You are already in a team for this hackathon");
        if (t.memberCount() >= t.maxSize())
            throw ApiException.conflict("TEAM_FULL", "This team is full");
        // uq_user_hackathon fires here if the same user wins two different teams at once
        jdbc.update("INSERT INTO team_members(team_id, user_id, hackathon_id) VALUES (?,?,?)", t.id(), userId, t.hackathonId());
        adjustCount(t.id(), +1);
    }

    private void adjustCount(UUID teamId, int delta) {
        jdbc.update("""
                UPDATE teams t SET member_count = t.member_count + ?,
                       status = CASE WHEN t.member_count + ? >= h.min_team_size THEN 'READY' ELSE 'FORMING' END
                FROM hackathons h WHERE h.id = t.hackathon_id AND t.id = ?""", delta, delta, teamId);
    }

    // ------------------------------------------------------------------ join requests

    @Transactional
    public RequestView requestToJoin(UUID userId, UUID teamId, String message) {
        List<Object[]> r = jdbc.query("SELECT hackathon_id, member_count, max_size FROM teams WHERE id = ?",
                (rs, i) -> new Object[]{Db.uuid(rs, "hackathon_id"), rs.getInt("member_count"), rs.getInt("max_size")}, teamId);
        if (r.isEmpty()) throw ApiException.notFound("TEAM_NOT_FOUND", "Team not found");
        UUID hackathonId = (UUID) r.get(0)[0];
        if (isInTeam(userId, hackathonId))
            throw ApiException.conflict("ALREADY_IN_TEAM", "You are already in a team for this hackathon");
        if ((Integer) r.get(0)[1] >= (Integer) r.get(0)[2])
            throw ApiException.conflict("TEAM_FULL", "This team is full");
        // uq_pending_request rejects a duplicate pending request
        UUID id = jdbc.queryForObject("INSERT INTO join_requests(team_id, user_id, message) VALUES (?,?,?) RETURNING id",
                UUID.class, teamId, userId, message == null || message.isBlank() ? null : message.trim());
        audit.record(teamId, userId, "REQUEST_CREATED", userId, null);
        events.publishEvent(new TeamChanged(teamId, hackathonId, "REQUEST_CREATED"));
        return new RequestView(id, userId, null, List.of(), message, "PENDING", Instant.now());
    }

    @Transactional(readOnly = true)
    public List<RequestView> listRequests(UUID userId, UUID teamId) {
        List<Object[]> t = jdbc.query("SELECT leader_id, hackathon_id FROM teams WHERE id = ?",
                (rs, i) -> new Object[]{Db.uuid(rs, "leader_id"), Db.uuid(rs, "hackathon_id")}, teamId);
        if (t.isEmpty()) throw ApiException.notFound("TEAM_NOT_FOUND", "Team not found");
        if (!userId.equals(t.get(0)[0])) throw ApiException.forbidden("NOT_LEADER", "Only the team leader can view requests");
        // requests from people who have meanwhile joined another team are hidden (checked lock-free at read time)
        return jdbc.query("""
                SELECT r.id, r.user_id, u.display_name, r.message, r.status, r.created_at,
                       COALESCE((SELECT array_agg(s.skill ORDER BY s.skill) FROM user_skills s WHERE s.user_id = u.id), '{}') AS skills
                FROM join_requests r JOIN users u ON u.id = r.user_id
                WHERE r.team_id = ? AND r.status = 'PENDING'
                  AND NOT EXISTS (SELECT 1 FROM team_members m WHERE m.user_id = r.user_id AND m.hackathon_id = ?)
                ORDER BY r.created_at""",
                (rs, i) -> new RequestView(Db.uuid(rs, "id"), Db.uuid(rs, "user_id"), rs.getString("display_name"),
                        Db.strings(rs, "skills"), rs.getString("message"), rs.getString("status"), Db.instant(rs, "created_at")),
                teamId, t.get(0)[1]);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public RequestDecision decide(UUID leaderId, UUID requestId, Decision decision) {
        List<UUID> teamIds = jdbc.query("SELECT team_id FROM join_requests WHERE id = ?", (rs, i) -> Db.uuid(rs, "team_id"), requestId);
        if (teamIds.isEmpty()) throw ApiException.notFound("REQUEST_NOT_FOUND", "Request not found");
        UUID teamId = teamIds.get(0);

        LockedTeam t = lock(teamId);                      // team first, always
        requireLeader(t, leaderId);                       // authorization checked on the locked, fresh row
        List<Object[]> rows = jdbc.query("SELECT user_id, status FROM join_requests WHERE id = ? FOR UPDATE",
                (rs, i) -> new Object[]{Db.uuid(rs, "user_id"), rs.getString("status")}, requestId);
        if (rows.isEmpty()) throw ApiException.notFound("REQUEST_NOT_FOUND", "Request not found");
        UUID applicant = (UUID) rows.get(0)[0];
        if (!"PENDING".equals(rows.get(0)[1]))
            throw ApiException.conflict("REQUEST_ALREADY_DECIDED", "This request has already been decided");

        jdbc.update("UPDATE join_requests SET status = ?, decided_at = now() WHERE id = ?", decision.name(), requestId);
        if (decision == Decision.ACCEPTED) {
            addMember(t, applicant);
            audit.record(teamId, leaderId, "REQUEST_ACCEPTED", applicant, null);
            audit.record(teamId, leaderId, "MEMBER_JOINED", applicant, Map.of("via", "request"));
        } else {
            audit.record(teamId, leaderId, "REQUEST_DECLINED", applicant, null);
        }
        events.publishEvent(new TeamChanged(teamId, t.hackathonId(), "REQUEST_" + decision.name()));
        return new RequestDecision(requestId, decision.name(), teamId);
    }

    // ------------------------------------------------------------------ leaving / removing / leadership

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public LeaveResult leave(UUID userId, UUID teamId) {
        LockedTeam t = lock(teamId);
        int removed = jdbc.update("DELETE FROM team_members WHERE team_id = ? AND user_id = ?", teamId, userId);
        if (removed == 0) throw ApiException.notFound("NOT_A_MEMBER", "You are not a member of this team");
        audit.record(teamId, userId, "MEMBER_LEFT", userId, null);

        if (t.memberCount() - 1 <= 0) {                   // last member out: the team goes with them
            audit.record(teamId, userId, "TEAM_DELETED", null, Map.of("reason", "last member left"));
            jdbc.update("DELETE FROM teams WHERE id = ?", teamId);   // cascades to invites + requests; audit survives
            events.publishEvent(new TeamChanged(teamId, t.hackathonId(), "TEAM_DELETED"));
            return new LeaveResult(true, null);
        }
        adjustCount(teamId, -1);
        UUID newLeader = null;
        if (t.leaderId().equals(userId)) {                // leadership passes to the longest-standing member
            newLeader = jdbc.queryForObject(
                    "SELECT user_id FROM team_members WHERE team_id = ? ORDER BY joined_at, user_id LIMIT 1", UUID.class, teamId);
            jdbc.update("UPDATE teams SET leader_id = ? WHERE id = ?", newLeader, teamId);
            audit.record(teamId, userId, "LEADER_TRANSFERRED", newLeader, Map.of("reason", "leader left"));
        }
        events.publishEvent(new TeamChanged(teamId, t.hackathonId(), "MEMBER_LEFT"));
        return new LeaveResult(false, newLeader);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void removeMember(UUID leaderId, UUID teamId, UUID targetId) {
        LockedTeam t = lock(teamId);
        requireLeader(t, leaderId);
        if (targetId.equals(t.leaderId()))
            throw ApiException.badRequest("CANNOT_REMOVE_LEADER", "The leader cannot be removed; leave the team instead");
        int removed = jdbc.update("DELETE FROM team_members WHERE team_id = ? AND user_id = ?", teamId, targetId);
        if (removed == 0) throw ApiException.notFound("NOT_A_MEMBER", "That user is not a member of this team");
        adjustCount(teamId, -1);
        audit.record(teamId, leaderId, "MEMBER_REMOVED", targetId, null);
        events.publishEvent(new TeamChanged(teamId, t.hackathonId(), "MEMBER_REMOVED"));
    }

    // ------------------------------------------------------------------ board / discovery / audit

    @Transactional(readOnly = true)
    public BoardPage board(UUID hackathonId, String query, String skill, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 50);
        int safePage = Math.max(page, 0);
        // Paginate on the partial index FIRST (no sort, ~0.1 ms), then join users for just the page's rows.
        StringBuilder sql = new StringBuilder("""
                SELECT t.id, t.hackathon_id, t.name, t.description, t.looking_for, t.max_size, t.member_count,
                       t.status, t.leader_id, u.display_name AS leader_name, t.created_at
                FROM (SELECT id, hackathon_id, name, description, looking_for, max_size, member_count, status, leader_id, created_at
                      FROM teams WHERE hackathon_id = ? AND member_count < max_size """);
        List<Object> args = new ArrayList<>();
        args.add(hackathonId);
        if (query != null && !query.isBlank()) {
            sql.append(" AND name ILIKE ? ESCAPE '\\'");
            args.add("%" + query.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        if (skill != null && !skill.isBlank()) {
            sql.append(" AND ? = ANY(looking_for)");
            args.add(skill.trim().toLowerCase());
        }
        sql.append(" ORDER BY created_at DESC, id LIMIT ? OFFSET ?) t JOIN users u ON u.id = t.leader_id ORDER BY t.created_at DESC, t.id");
        args.add(safeSize + 1);                           // fetch one extra row to know if there is a next page
        args.add((long) safePage * safeSize);
        List<TeamSummary> rows = jdbc.query(sql.toString(), SUMMARY, args.toArray());
        boolean more = rows.size() > safeSize;
        return new BoardPage(more ? rows.subList(0, safeSize) : rows, safePage, safeSize, more);
    }

    @Transactional(readOnly = true)
    public TeamSummary myTeam(UUID userId, UUID hackathonId) {
        List<TeamSummary> r = jdbc.query(SUMMARY_SELECT
                + "JOIN team_members m ON m.team_id = t.id WHERE m.user_id = ? AND m.hackathon_id = ?", SUMMARY, userId, hackathonId);
        return r.isEmpty() ? null : r.get(0);
    }

    @Transactional(readOnly = true)
    public List<AuditService.Entry> auditLog(UUID userId, UUID teamId) {
        requireMember(teamId, userId);
        return audit.list(teamId);
    }

    // ------------------------------------------------------------------ helpers

    public void requireMember(UUID teamId, UUID userId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM team_members WHERE team_id = ? AND user_id = ?", Integer.class, teamId, userId);
        if (n == null || n == 0) throw ApiException.forbidden("NOT_A_MEMBER", "Only team members can do that");
    }

    private boolean isInTeam(UUID userId, UUID hackathonId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM team_members WHERE user_id = ? AND hackathon_id = ?",
                Integer.class, userId, hackathonId);
        return n != null && n > 0;
    }

    private void requireLeader(LockedTeam t, UUID userId) {
        if (!t.leaderId().equals(userId)) throw ApiException.forbidden("NOT_LEADER", "Only the team leader can do that");
    }

    /** SELECT ... FOR UPDATE on the team row. In READ COMMITTED a waiter re-reads the latest committed row. */
    private LockedTeam lock(UUID teamId) {
        List<LockedTeam> r = jdbc.query(
                "SELECT id, hackathon_id, leader_id, max_size, member_count FROM teams WHERE id = ? FOR UPDATE",
                (rs, i) -> new LockedTeam(Db.uuid(rs, "id"), Db.uuid(rs, "hackathon_id"), Db.uuid(rs, "leader_id"),
                        rs.getInt("max_size"), rs.getInt("member_count")), teamId);
        if (r.isEmpty()) throw ApiException.notFound("TEAM_NOT_FOUND", "Team not found");
        return r.get(0);
    }
}
