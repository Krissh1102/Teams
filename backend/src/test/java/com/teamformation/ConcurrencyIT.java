package com.teamformation;

import static org.junit.jupiter.api.Assertions.*;

import com.teamformation.Api.Resp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/** The tests that matter most: correctness when many people act at the same instant. */
class ConcurrencyIT extends AbstractIT {

    /** 4 of 5 seats taken, 50 people click "join" at once: exactly one gets the last seat. */
    @RepeatedTest(5)
    void fiftyPeopleRaceForTheLastSeat() throws Exception {
        UUID h = newHackathon(2, 5);
        User leader = newUser();
        UUID team = team(leader, h, 5);
        String code = invite(leader, team);
        for (User u : newUsers(3)) joinOk(u, team, code);
        assertEquals(4, rows(team));

        List<User> contenders = newUsers(50);
        List<Resp> results = race(50, i -> join(contenders.get(i), team, code));

        assertEquals(1, count(results, 200), "exactly one success");
        assertEquals(49, count(results, 409), "everyone else rejected");
        assertTrue(results.stream().filter(r -> r.status() == 409).allMatch(r -> "TEAM_FULL".equals(r.code())));
        assertEquals(5, rows(team), "final members");
        assertEquals(5, countColumn(team), "member_count column agrees with rows");
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) - count(DISTINCT user_id) FROM team_members WHERE team_id = ?", Integer.class, team), "no duplicates");
    }

    /** One person, twenty teams, twenty simultaneous joins: the unique index lets exactly one through. */
    @Test
    void sameUserRacingAcrossManyTeamsJoinsOnlyOne() throws Exception {
        UUID h = newHackathon(2, 5);
        List<UUID> teams = new ArrayList<>();
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            User leader = newUser();
            UUID t = team(leader, h, 5);
            teams.add(t);
            codes.add(invite(leader, t));
        }
        User runner = newUser();
        List<Resp> results = race(20, i -> join(runner, teams.get(i), codes.get(i)));

        assertEquals(1, count(results, 200));
        assertEquals(19, count(results, 409));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM team_members WHERE user_id = ? AND hackathon_id = ?",
                Integer.class, runner.id(), h));
        for (UUID t : teams) assertEquals(rows(t), countColumn(t), "no counter drift on the losing teams");
    }

    /** A double-click / retry storm with the same Idempotency-Key: one join, nineteen replays. */
    @Test
    void sameIdempotencyKeySentTwentyTimesJoinsOnce() throws Exception {
        UUID h = newHackathon(2, 5);
        User leader = newUser();
        UUID team = team(leader, h, 5);
        String code = invite(leader, team);
        User u = newUser();

        List<Resp> results = race(20, i -> api.post("/api/teams/" + team + "/join", u.token(), Map.of("inviteCode", code), "retry-storm-key"));

        assertEquals(20, count(results, 200));
        assertEquals(19, results.stream().filter(Resp::replayed).count());
        assertEquals(2, rows(team));
        assertEquals(2, countColumn(team));
    }

    /** Leader accepts three requests at once; only one seat is left. */
    @Test
    void acceptingThreeRequestsForOneSeatAdmitsExactlyOne() throws Exception {
        UUID h = newHackathon(2, 3);
        User leader = newUser();
        UUID team = team(leader, h, 3);
        joinOk(newUser(), team, invite(leader, team));   // 2 of 3
        List<User> applicants = newUsers(3);
        List<String> requestIds = new ArrayList<>();
        for (User a : applicants) {
            Resp r = api.post("/api/teams/" + team + "/requests", a.token(), Map.of("message", "pick me"));
            assertEquals(201, r.status());
            requestIds.add(r.body().get("id").asText());
        }
        List<Resp> results = race(3, i -> api.patch("/api/requests/" + requestIds.get(i), leader.token(), Map.of("status", "ACCEPTED")));

        assertEquals(1, count(results, 200));
        assertEquals(2, count(results, 409));
        assertEquals(3, rows(team));
        assertEquals(3, countColumn(team));
    }

    /** Ten simultaneous "create team" calls by one user (different keys): one team, nine conflicts. */
    @Test
    void concurrentCreateTeamBySameUserCreatesOne() throws Exception {
        UUID h = newHackathon(2, 5);
        User u = newUser();
        List<Resp> results = race(10, i -> api.post("/api/teams", u.token(),
                Map.of("hackathonId", h, "name", "Dup team " + i), "create-" + i));
        assertEquals(1, count(results, 201));
        assertEquals(9, count(results, 409));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM teams WHERE leader_id = ? AND hackathon_id = ?", Integer.class, u.id(), h));
    }

    /** Joiners and leavers interleaved: counters and rows never diverge, capacity is never exceeded. */
    @Test
    void joinsAndLeavesInterleavedKeepCountersConsistent() throws Exception {
        UUID h = newHackathon(2, 5);
        User leader = newUser();
        UUID team = team(leader, h, 5);
        String code = invite(leader, team);
        List<User> members = newUsers(3);
        for (User m : members) joinOk(m, team, code);
        List<User> joiners = newUsers(20);

        race(23, i -> i < 3 ? api.delete("/api/teams/" + team + "/leave", members.get(i).token())
                            : join(joiners.get(i - 3), team, code));

        assertEquals(rows(team), countColumn(team));
        assertTrue(rows(team) <= 5);
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM teams WHERE member_count > max_size", Integer.class));
    }
}
