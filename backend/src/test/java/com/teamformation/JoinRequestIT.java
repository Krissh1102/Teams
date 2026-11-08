package com.teamformation;

import static org.junit.jupiter.api.Assertions.*;

import com.teamformation.Api.Resp;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JoinRequestIT extends AbstractIT {

    private String request(User applicant, UUID team) {
        Resp r = api.post("/api/teams/" + team + "/requests", applicant.token(), Map.of("message", "I know Spring"));
        assertEquals(201, r.status(), r.body().toString());
        return r.body().get("id").asText();
    }

    private Resp decide(User by, String requestId, String status) {
        return api.patch("/api/requests/" + requestId, by.token(), Map.of("status", status));
    }

    @Test
    void leaderAcceptsARequestAndTheApplicantBecomesAMember() {
        UUID h = newHackathon(2, 5);
        User leader = newUser(), applicant = newUser();
        UUID team = team(leader, h, 5);
        String id = request(applicant, team);

        Resp list = api.get("/api/teams/" + team + "/requests", leader.token());
        assertEquals(200, list.status());
        assertEquals(1, list.body().size());

        assertEquals(200, decide(leader, id, "ACCEPTED").status());
        assertEquals(2, rows(team));
        assertEquals(2, countColumn(team));
        assertEquals(0, api.get("/api/teams/" + team + "/requests", leader.token()).body().size(), "no longer pending");
        assertEquals("REQUEST_ALREADY_DECIDED", decide(leader, id, "ACCEPTED").code(), "cannot decide twice");
    }

    @Test
    void leaderDeclinesARequestAndTheApplicantIsNotAdded() {
        UUID h = newHackathon(2, 5);
        User leader = newUser(), applicant = newUser();
        UUID team = team(leader, h, 5);
        String id = request(applicant, team);
        assertEquals(200, decide(leader, id, "DECLINED").status());
        assertEquals(1, rows(team));
        assertEquals("DECLINED", jdbc.queryForObject("SELECT status FROM join_requests WHERE id = ?::uuid", String.class, id));
    }

    @Test
    void onlyTheLeaderCanDecideOrListRequests() {
        UUID h = newHackathon(2, 5);
        User leader = newUser(), member = newUser(), applicant = newUser();
        UUID team = team(leader, h, 5);
        joinOk(member, team, invite(leader, team));
        String id = request(applicant, team);

        Resp d = decide(member, id, "ACCEPTED");
        assertEquals(403, d.status());
        assertEquals("NOT_LEADER", d.code());
        assertEquals(403, decide(applicant, id, "ACCEPTED").status());
        assertEquals(403, api.get("/api/teams/" + team + "/requests", member.token()).status());
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM join_requests WHERE id = ?::uuid", String.class, id));
    }

    @Test
    void duplicatePendingRequestsAreRejected() {
        UUID h = newHackathon(2, 5);
        User leader = newUser(), applicant = newUser();
        UUID team = team(leader, h, 5);
        request(applicant, team);
        Resp dup = api.post("/api/teams/" + team + "/requests", applicant.token(), Map.of("message", "again"));
        assertEquals(409, dup.status());
        assertEquals("REQUEST_PENDING", dup.code());
    }

    @Test
    void membersAndFullTeamsCannotBeRequested() {
        UUID h = newHackathon(2, 2);
        User leader = newUser(), member = newUser();
        UUID team = team(leader, h, 2);
        joinOk(member, team, invite(leader, team));
        assertEquals("ALREADY_IN_TEAM", api.post("/api/teams/" + team + "/requests", member.token(), Map.of()).code());
        assertEquals("TEAM_FULL", api.post("/api/teams/" + team + "/requests", newUser().token(), Map.of()).code());
    }

    @Test
    void acceptingIntoATeamThatFilledUpIsRejectedAndTheRequestStaysPending() {
        UUID h = newHackathon(2, 2);
        User leader = newUser(), applicant = newUser();
        UUID team = team(leader, h, 2);
        String id = request(applicant, team);                       // seat still free at request time
        joinOk(newUser(), team, invite(leader, team));              // ...then the seat is taken
        Resp r = decide(leader, id, "ACCEPTED");
        assertEquals(409, r.status());
        assertEquals("TEAM_FULL", r.code());
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM join_requests WHERE id = ?::uuid", String.class, id));
    }

    @Test
    void anApplicantWhoJoinedElsewhereCannotBeAcceptedAndDisappearsFromTheList() {
        UUID h = newHackathon(2, 5);
        User l1 = newUser(), l2 = newUser(), applicant = newUser();
        UUID t1 = team(l1, h, 5), t2 = team(l2, h, 5);
        String id1 = request(applicant, t1);
        joinOk(applicant, t2, invite(l2, t2));
        assertEquals(0, api.get("/api/teams/" + t1 + "/requests", l1.token()).body().size());
        assertEquals("ALREADY_IN_TEAM", decide(l1, id1, "ACCEPTED").code());
    }
}
