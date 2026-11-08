package com.teamformation.team;

import com.teamformation.audit.AuditService;
import com.teamformation.idempotency.IdempotencyService;
import com.teamformation.idempotency.IdempotencyService.Outcome;
import com.teamformation.team.TeamDtos.*;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class TeamController {
    private final TeamService teams;
    private final IdempotencyService idem;

    public TeamController(TeamService teams, IdempotencyService idem) { this.teams = teams; this.idem = idem; }

    static ResponseEntity<Object> respond(Outcome o) {
        ResponseEntity.BodyBuilder b = ResponseEntity.status(o.status());
        if (o.replayed()) b.header("Idempotent-Replay", "true");
        return b.body(o.body());
    }

    @PostMapping("/teams")
    public ResponseEntity<Object> create(@AuthenticationPrincipal UUID userId, @Valid @RequestBody CreateTeamRequest req,
                                         @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return respond(idem.run(userId, key, "create-team", req.toString(),
                () -> Outcome.ok(201, teams.create(userId, req))));
    }

    @GetMapping("/teams")
    public BoardPage discover(@RequestParam UUID hackathonId, @RequestParam(required = false) String q,
                              @RequestParam(required = false) String skill,
                              @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return teams.board(hackathonId, q, skill, page, size);
    }

    @GetMapping("/teams/{id}")
    public TeamDetail get(@PathVariable UUID id) { return teams.get(id); }

    @PatchMapping("/teams/{id}")
    public TeamDetail update(@AuthenticationPrincipal UUID userId, @PathVariable UUID id, @Valid @RequestBody UpdateTeamRequest req) {
        return teams.update(userId, id, req);
    }

    @PostMapping("/teams/{id}/join")
    public ResponseEntity<Object> join(@AuthenticationPrincipal UUID userId, @PathVariable UUID id, @Valid @RequestBody JoinBody body,
                                       @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return respond(idem.run(userId, key, "join:" + id, body.inviteCode(),
                () -> Outcome.ok(200, teams.joinWithInvite(userId, id, body.inviteCode()))));
    }

    @PostMapping("/teams/{id}/invites")
    @ResponseStatus(HttpStatus.CREATED)
    public InviteView invite(@AuthenticationPrincipal UUID userId, @PathVariable UUID id) { return teams.createInvite(userId, id); }

    @PostMapping("/teams/{id}/requests")
    public ResponseEntity<Object> requestToJoin(@AuthenticationPrincipal UUID userId, @PathVariable UUID id,
                                                @Valid @RequestBody(required = false) RequestToJoinBody body,
                                                @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        String msg = body == null ? null : body.message();
        return respond(idem.run(userId, key, "request:" + id, String.valueOf(msg),
                () -> Outcome.ok(201, teams.requestToJoin(userId, id, msg))));
    }

    @GetMapping("/teams/{id}/requests")
    public List<RequestView> requests(@AuthenticationPrincipal UUID userId, @PathVariable UUID id) {
        return teams.listRequests(userId, id);
    }

    @PatchMapping("/requests/{requestId}")
    public ResponseEntity<Object> decide(@AuthenticationPrincipal UUID userId, @PathVariable UUID requestId,
                                         @Valid @RequestBody DecisionBody body,
                                         @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return respond(idem.run(userId, key, "decide:" + requestId, body.status().name(),
                () -> Outcome.ok(200, teams.decide(userId, requestId, body.status()))));
    }

    @DeleteMapping("/teams/{id}/members/{memberId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@AuthenticationPrincipal UUID userId, @PathVariable UUID id, @PathVariable UUID memberId) {
        teams.removeMember(userId, id, memberId);
    }

    @DeleteMapping("/teams/{id}/leave")
    public LeaveResult leave(@AuthenticationPrincipal UUID userId, @PathVariable UUID id) { return teams.leave(userId, id); }

    @GetMapping("/teams/{id}/audit")
    public List<AuditService.Entry> audit(@AuthenticationPrincipal UUID userId, @PathVariable UUID id) {
        return teams.auditLog(userId, id);
    }
}
