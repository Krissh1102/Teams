package com.teamformation;

import static org.junit.jupiter.api.Assertions.*;

import com.teamformation.Api.Resp;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LeadershipIT extends AbstractIT {

    @Test
    void whenTheLeaderLeavesTheLongestStandingMemberTakesOver() {
        UUID h = newHackathon(2, 5);
        User leader = newUser(), first = newUser(), second = newUser();
        UUID team = team(leader, h, 5);
        String code = invite(leader, team);
        joinOk(first, team, code);
        joinOk(second, team, code);

        Resp r = api.delete("/api/teams/" + team + "/leave", leader.token());
        assertEquals(200, r.status());
        assertFalse(r.body().get("teamDeleted").asBoolean());
        assertEquals(first.id().toString(), r.body().get("newLeaderId").asText());

        Resp t = api.get("/api/teams/" + team, first.token());
        assertEquals(first.id().toString(), t.body().get("team").get("leaderId").asText());
        assertEquals(2, t.body().get("team").get("memberCount").asInt());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM team_audit_logs WHERE team_id = ? AND action = 'LEADER_TRANSFERRED'", Integer.class, team));
        // and the new leader really has the powers
        assertEquals(201, api.post("/api/teams/" + team + "/invites", first.token(), null).status());
        assertEquals(403, api.post("/api/teams/" + team + "/invites", leader.token(), null).status());
    }

    @Test
    void whenTheLastMemberLeavesTheTeamIsDeletedButItsHistoryRemains() {
        UUID h = newHackathon(2, 5);
        User leader = newUser();
        UUID team = team(leader, h, 5);
        Resp r = api.delete("/api/teams/" + team + "/leave", leader.token());
        assertEquals(200, r.status());
        assertTrue(r.body().get("teamDeleted").asBoolean());
        assertEquals(404, api.get("/api/teams/" + team, leader.token()).status());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM teams WHERE id = ?", Integer.class, team));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM team_audit_logs WHERE team_id = ? AND action = 'TEAM_DELETED'", Integer.class, team));
        assertEquals(201, api.post("/api/teams", leader.token(), java.util.Map.of("hackathonId", h, "name", "Fresh start")).status(),
                "the former member is free to create or join again");
    }

    @Test
    void anOrdinaryMemberLeavingKeepsTheLeaderAndFreesTheSeat() {
        UUID h = newHackathon(2, 3);
        User leader = newUser(), member = newUser();
        UUID team = team(leader, h, 3);
        joinOk(member, team, invite(leader, team));
        Resp r = api.delete("/api/teams/" + team + "/leave", member.token());
        assertEquals(200, r.status());
        assertFalse(r.body().has("newLeaderId"));
        assertEquals(1, rows(team));
        assertEquals(1, countColumn(team));
        assertEquals("FORMING", jdbc.queryForObject("SELECT status FROM teams WHERE id = ?", String.class, team), "dropped below the minimum");
    }

    @Test
    void onlyTheLeaderCanRemoveMembersAndNeverTheLeader() {
        UUID h = newHackathon(2, 5);
        User leader = newUser(), a = newUser(), b = newUser();
        UUID team = team(leader, h, 5);
        String code = invite(leader, team);
        joinOk(a, team, code);
        joinOk(b, team, code);

        assertEquals(403, api.delete("/api/teams/" + team + "/members/" + b.id(), a.token()).status());
        assertEquals("CANNOT_REMOVE_LEADER", api.delete("/api/teams/" + team + "/members/" + leader.id(), leader.token()).code());
        assertEquals(204, api.delete("/api/teams/" + team + "/members/" + b.id(), leader.token()).status());
        assertEquals(404, api.delete("/api/teams/" + team + "/members/" + b.id(), leader.token()).status(), "already removed");
        assertEquals(2, rows(team));
        assertEquals(2, countColumn(team));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM team_audit_logs WHERE team_id = ? AND action = 'MEMBER_REMOVED'", Integer.class, team));
    }

    @Test
    void leavingATeamYouAreNotInIsRejected() {
        UUID h = newHackathon(2, 5);
        User leader = newUser();
        UUID team = team(leader, h, 5);
        Resp r = api.delete("/api/teams/" + team + "/leave", newUser().token());
        assertEquals(404, r.status());
        assertEquals("NOT_A_MEMBER", r.code());
        assertEquals(1, rows(team));
    }
}
