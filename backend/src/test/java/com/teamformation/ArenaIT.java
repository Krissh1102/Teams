package com.teamformation;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.teamformation.Api.Resp;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** The Race Arena is itself tested: the demo must show the truth, and must clean up after itself. */
@TestPropertySource(properties = "app.arena.enabled=true")
class ArenaIT extends AbstractIT {

    private JsonNode race(User u, String mode, int contenders) {
        Resp r = api.post("/api/arena/race", u.token(), Map.of("mode", mode, "contenders", contenders, "capacity", 5));
        assertEquals(200, r.status(), r.body().toString());
        return r.body();
    }

    @Test
    void safeModeFiftyContendersExactlyOneWinnerAndNothingCorrupted() {
        JsonNode r = race(newUser(), "SAFE", 50);
        assertEquals(1, r.get("joined").asInt());
        assertEquals(49, r.get("rejected").asInt());
        assertEquals(0, r.get("errors").asInt());
        assertEquals(5, r.get("finalMembers").asInt());
        assertEquals(5, r.get("memberCounter").asInt());
        assertEquals(0, r.get("duplicates").asInt());
        assertEquals(0, r.get("capacityViolations").asInt());
        assertTrue(r.get("consistent").asBoolean());
        assertEquals(50, r.get("attempts").size());
        r.get("checks").forEach(c -> assertTrue(c.get("pass").asBoolean(), c.toString()));
    }

    @Test
    void withoutTheLockTheCheckConstraintStillPreventsOverbooking() {
        JsonNode r = race(newUser(), "NO_LOCK", 50);
        assertEquals(1, r.get("joined").asInt(), "the CHECK constraint lets exactly one through");
        assertEquals(5, r.get("finalMembers").asInt());
        assertTrue(r.get("consistent").asBoolean());
    }

    @Test
    void aNaiveImplementationReallyDoesOverbook() {
        JsonNode r = race(newUser(), "NAIVE", 50);
        assertTrue(r.get("joined").asInt() > 1, "several people were given the one seat");
        assertTrue(r.get("capacityViolations").asInt() > 0);
        assertFalse(r.get("consistent").asBoolean());
        assertTrue(r.get("verdict").asText().startsWith("CORRUPTED"));
    }

    @Test
    void arenaLeavesNothingBehindAndStaysOutOfTheRealHackathonList() {
        User u = newUser();
        race(u, "NAIVE", 20);   // the messiest mode
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM users WHERE email LIKE '%@arena.test'", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM hackathons WHERE name LIKE 'arena-%'", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM teams WHERE name = 'Last Seat Team'", Integer.class));
        Resp list = api.get("/api/hackathons", u.token());
        list.body().forEach(h -> assertFalse(h.get("name").asText().startsWith("arena-")));
    }

    @Test
    void arenaValidatesInputAndRequiresLogin() {
        User u = newUser();
        assertEquals(400, api.post("/api/arena/race", u.token(), Map.of("mode", "SAFE", "contenders", 1)).status());
        assertEquals(400, api.post("/api/arena/race", u.token(), Map.of("mode", "SAFE", "contenders", 5000)).status());
        assertEquals(400, api.post("/api/arena/race", u.token(), Map.of("mode", "BOGUS", "contenders", 10)).status());
        assertEquals(401, api.post("/api/arena/race", null, Map.of("mode", "SAFE")).status());
        assertEquals(200, api.get("/api/arena/status", u.token()).status());
    }
}
