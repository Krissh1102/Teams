package com.teamformation;

import static org.junit.jupiter.api.Assertions.*;

import com.teamformation.Api.Resp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TeamMembershipIT extends AbstractIT {

    @Test
    void creatingATeamMakesTheCreatorLeaderAndFirstMember() {
        UUID h = newHackathon(2, 5);
        User leader = newUser();
        Resp r = api.post("/api/teams", leader.token(), Map.of("hackathonId", h, "name", "Rustaceans",
                "lookingFor", List.of("UI", "ml"), "maxSize", 4));
        assertEquals(201, r.status());
        assertEquals(1, r.body().get("team").get("memberCount").asInt());
        assertEquals("FORMING", r.body().get("team").get("status").asText(), "below the minimum size");
        assertEquals(leader.id().toString(), r.body().get("team").get("leaderId").asText());
        assertEquals("ui", r.body().get("team").get("lookingFor").get(0).asText());
        assertTrue(r.body().get("members").get(0).get("leader").asBoolean());
    }

    @Test
    void teamSizeAndInputsAreValidated() {
        UUID h = newHackathon(2, 5);
        User u = newUser();
        assertEquals("INVALID_TEAM_SIZE", api.post("/api/teams", u.token(), Map.of("hackathonId", h, "name", "Big", "maxSize", 9)).code());
        assertEquals("INVALID_TEAM_SIZE", api.post("/api/teams", u.token(), Map.of("hackathonId", h, "name", "Tiny", "maxSize", 1)).code());
        assertEquals("VALIDATION_FAILED", api.post("/api/teams", u.token(), Map.of("hackathonId", h, "name", " ")).code());
        assertEquals(404, api.post("/api/teams", u.token(), Map.of("hackathonId", UUID.randomUUID(), "name", "Ghost")).status());
    }

    @Test
    void aPersonCannotCreateASecondTeamInTheSameHackathon() {
        UUID h = newHackathon(2, 5);
        User u = newUser();
        team(u, h, 5);
        Resp second = api.post("/api/teams", u.token(), Map.of("hackathonId", h, "name", "Another"));
        assertEquals(409, second.status());
        assertEquals("ALREADY_IN_TEAM", second.code());
        assertEquals(201, api.post("/api/teams", u.token(), Map.of("hackathonId", newHackathon(2, 5), "name", "Elsewhere")).status(),
                "a different hackathon is fine");
    }

    @Test
    void joiningWithAValidInviteAddsTheMemberAndMarksTheTeamReady() {
        UUID h = newHackathon(2, 5);
        User leader = newUser();
        UUID team = team(leader, h, 5);
        User u = newUser();
        Resp r = join(u, team, invite(leader, team));
        assertEquals(200, r.status());
        assertEquals(2, r.body().get("team").get("memberCount").asInt());
        assertEquals("READY", r.body().get("team").get("status").asText());
        assertEquals(409, join(u, team, invite(leader, team)).status(), "joining twice is rejected");
    }

    @Test
    void aMemberOfOneTeamCannotJoinAnother() {
        UUID h = newHackathon(2, 5);
        User l1 = newUser(), l2 = newUser(), u = newUser();
        UUID t1 = team(l1, h, 5), t2 = team(l2, h, 5);
        joinOk(u, t1, invite(l1, t1));
        Resp r = join(u, t2, invite(l2, t2));
        assertEquals(409, r.status());
        assertEquals("ALREADY_IN_TEAM", r.code());
    }

    @Test
    void aFullTeamRejectsFurtherJoins() {
        UUID h = newHackathon(2, 2);
        User leader = newUser();
        UUID team = team(leader, h, 2);
        String code = invite(leader, team);
        joinOk(newUser(), team, code);
        Resp r = join(newUser(), team, code);
        assertEquals(409, r.status());
        assertEquals("TEAM_FULL", r.code());
        assertEquals(2, rows(team));
    }

    @Test
    void onlyTheLeaderCanCreateInvitesOrEditTheTeam() {
        UUID h = newHackathon(2, 5);
        User leader = newUser(), member = newUser(), outsider = newUser();
        UUID team = team(leader, h, 5);
        joinOk(member, team, invite(leader, team));
        assertEquals(403, api.post("/api/teams/" + team + "/invites", member.token(), null).status());
        assertEquals(403, api.post("/api/teams/" + team + "/invites", outsider.token(), null).status());
        assertEquals(403, api.patch("/api/teams/" + team, member.token(), Map.of("name", "Hijacked")).status());
        Resp ok = api.patch("/api/teams/" + team, leader.token(), Map.of("name", "Renamed", "lookingFor", List.of("Go")));
        assertEquals(200, ok.status());
        assertEquals("Renamed", ok.body().get("team").get("name").asText());
    }

    @Test
    void unknownTeamIs404AndBadIdsAre400() {
        User u = newUser();
        assertEquals(404, api.get("/api/teams/" + UUID.randomUUID(), u.token()).status());
        assertEquals(400, api.get("/api/teams/not-a-uuid", u.token()).status());
    }

    // ---------------------------------------------------------------- invite validation

    @Test
    void wrongInviteCodeIsRejected() {
        UUID h = newHackathon(2, 5);
        User leader = newUser();
        UUID team = team(leader, h, 5);
        Resp r = join(newUser(), team, "ZZZZZZZZZZZZZZZZZZZZZZ");
        assertEquals(400, r.status());
        assertEquals("INVALID_INVITE", r.code());
        assertEquals(400, api.post("/api/teams/" + team + "/join", newUser().token(), Map.of("inviteCode", "short")).status(), "too short fails validation");
    }

    @Test
    void expiredInviteIsRejected() {
        UUID h = newHackathon(2, 5);
        User leader = newUser();
        UUID team = team(leader, h, 5);
        String code = invite(leader, team);
        jdbc.update("UPDATE team_invites SET expires_at = now() - interval '1 hour' WHERE team_id = ?", team);
        Resp r = join(newUser(), team, code);
        assertEquals(400, r.status());
        assertEquals("INVALID_INVITE", r.code());
    }

    @Test
    void anInviteOnlyWorksForItsOwnTeam() {
        UUID h = newHackathon(2, 5);
        User l1 = newUser(), l2 = newUser();
        UUID t1 = team(l1, h, 5), t2 = team(l2, h, 5);
        String codeOfT1 = invite(l1, t1);
        assertEquals("INVALID_INVITE", join(newUser(), t2, codeOfT1).code());
    }

    @Test
    void regeneratingRevokesTheOldCodeAndCodesAreHighEntropyAndStoredHashed() {
        UUID h = newHackathon(2, 5);
        User leader = newUser();
        UUID team = team(leader, h, 5);
        String oldCode = invite(leader, team);
        String newCode = invite(leader, team);
        assertNotEquals(oldCode, newCode);
        assertEquals(22, newCode.length(), "16 random bytes (128 bits), base64url");
        assertEquals("INVALID_INVITE", join(newUser(), team, oldCode).code());
        assertEquals(200, join(newUser(), team, newCode).status());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM team_invites WHERE code_hash = ?", Integer.class, newCode),
                "the raw code is never stored");
        assertEquals(64, jdbc.queryForObject("SELECT length(code_hash) FROM team_invites WHERE team_id = ? LIMIT 1", Integer.class, team));
    }
}
