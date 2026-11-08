package com.teamformation;

import static org.junit.jupiter.api.Assertions.*;

import com.teamformation.Api.Resp;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdempotencyIT extends AbstractIT {

    @Test
    void retriedJoinWithTheSameKeyIsReplayedNotRepeated() {
        UUID h = newHackathon(2, 5);
        User leader = newUser(), u = newUser();
        UUID team = team(leader, h, 5);
        String code = invite(leader, team);
        Map<String, String> body = Map.of("inviteCode", code);

        Resp first = api.post("/api/teams/" + team + "/join", u.token(), body, "join-1");
        Resp retry = api.post("/api/teams/" + team + "/join", u.token(), body, "join-1");
        assertEquals(200, first.status());
        assertEquals(200, retry.status());
        assertFalse(first.replayed());
        assertTrue(retry.replayed());
        assertEquals(first.body(), retry.body());
        assertEquals(2, rows(team));
    }

    @Test
    void retriedCreateTeamWithTheSameKeyCreatesOneTeam() {
        UUID h = newHackathon(2, 5);
        User u = newUser();
        Map<String, Object> body = Map.of("hackathonId", h, "name", "Double click");
        Resp a = api.post("/api/teams", u.token(), body, "create-1");
        Resp b = api.post("/api/teams", u.token(), body, "create-1");
        assertEquals(201, a.status());
        assertEquals(201, b.status());
        assertTrue(b.replayed());
        assertEquals(a.body().get("team").get("id"), b.body().get("team").get("id"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM teams WHERE leader_id = ?", Integer.class, u.id()));
    }

    @Test
    void reusingAKeyForADifferentRequestIsRejected() {
        UUID h = newHackathon(2, 5);
        User u = newUser();
        assertEquals(201, api.post("/api/teams", u.token(), Map.of("hackathonId", h, "name", "One"), "k-1").status());
        Resp other = api.post("/api/teams", u.token(), Map.of("hackathonId", h, "name", "Two"), "k-1");
        assertEquals(422, other.status());
        assertEquals("IDEMPOTENCY_KEY_REUSED", other.code());
    }

    @Test
    void keysAreScopedPerUserAndAFailedAttemptDoesNotBurnTheKey() {
        UUID h = newHackathon(2, 5);
        User leader = newUser(), a = newUser(), b = newUser();
        UUID team = team(leader, h, 5);
        String code = invite(leader, team);
        Map<String, String> bad = Map.of("inviteCode", "ZZZZZZZZZZZZZZZZZZZZZZ");

        assertEquals(400, api.post("/api/teams/" + team + "/join", a.token(), bad, "shared").status());
        // same key, now with the right code: executes (the failed attempt rolled its key back)
        assertEquals(200, api.post("/api/teams/" + team + "/join", a.token(), Map.of("inviteCode", code), "shared").status());
        // another user may use the same key string independently
        Resp other = api.post("/api/teams/" + team + "/join", b.token(), Map.of("inviteCode", code), "shared");
        assertEquals(200, other.status());
        assertFalse(other.replayed());
        assertEquals(3, rows(team));
    }

    @Test
    void malformedKeysAreRejected() {
        UUID h = newHackathon(2, 5);
        User u = newUser();
        Resp r = api.post("/api/teams", u.token(), Map.of("hackathonId", h, "name", "X1"), "bad key with spaces!");
        assertEquals(400, r.status());
        assertEquals("INVALID_IDEMPOTENCY_KEY", r.code());
    }
}
