package com.teamformation;

import static org.junit.jupiter.api.Assertions.*;

import com.teamformation.Api.Resp;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Default configuration: the arena does not exist, and client errors are never reported as 500s. */
class ArenaDisabledIT extends AbstractIT {

    @Test
    void arenaEndpointsAreAbsentUnlessExplicitlyEnabled() {
        User u = newUser();
        Resp r = api.get("/api/arena/status", u.token());
        assertEquals(404, r.status());
        assertEquals("NOT_FOUND", r.code());
        assertEquals(404, api.post("/api/arena/race", u.token(), Map.of("mode", "NAIVE")).status());
    }

    @Test
    void unknownRoutesWrongMethodsAndMissingParamsAreClientErrorsNot500() {
        User u = newUser();
        assertEquals(404, api.get("/api/definitely-not-a-route", u.token()).status());
        assertEquals(405, api.send("PUT", "/api/hackathons", u.token(), Map.of(), null).status());
        assertEquals(400, api.get("/api/teams", u.token()).status(), "hackathonId is required");
    }
}
