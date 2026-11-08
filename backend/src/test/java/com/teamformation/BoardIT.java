package com.teamformation;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.teamformation.Api.Resp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BoardIT extends AbstractIT {

    @Test
    void boardListsOnlyTeamsWithAnOpenSeat() {
        UUID h = newHackathon(2, 2);
        User l1 = newUser(), l2 = newUser();
        UUID open = team(l1, h, 2);
        UUID full = team(l2, h, 2);
        joinOk(newUser(), full, invite(l2, full));

        Resp r = api.get("/api/hackathons/" + h + "/board", newUser().token());
        assertEquals(200, r.status());
        assertEquals(1, r.body().get("items").size());
        assertEquals(open.toString(), r.body().get("items").get(0).get("id").asText());
    }

    @Test
    void boardIsPaginatedNewestFirst() {
        UUID h = newHackathon(2, 5);
        for (int i = 0; i < 5; i++) team(newUser(), h, 5);
        User viewer = newUser();
        JsonNode p0 = api.get("/api/hackathons/" + h + "/board?size=2&page=0", viewer.token()).body();
        JsonNode p2 = api.get("/api/hackathons/" + h + "/board?size=2&page=2", viewer.token()).body();
        assertEquals(2, p0.get("items").size());
        assertTrue(p0.get("hasMore").asBoolean());
        assertEquals(1, p2.get("items").size());
        assertFalse(p2.get("hasMore").asBoolean());
        assertEquals(5, api.get("/api/hackathons/" + h + "/board?size=9999", viewer.token()).body().get("items").size(), "size is clamped, not trusted");
    }

    @Test
    void boardFiltersBySkillAndNameAndTreatsWildcardsAsText() {
        UUID h = newHackathon(2, 5);
        User a = newUser(), b = newUser();
        api.post("/api/teams", a.token(), Map.of("hackathonId", h, "name", "Data wizards", "lookingFor", List.of("ML")));
        api.post("/api/teams", b.token(), Map.of("hackathonId", h, "name", "Pixel pushers", "lookingFor", List.of("UI")));
        User v = newUser();
        assertEquals(1, api.get("/api/hackathons/" + h + "/board?skill=ml", v.token()).body().get("items").size());
        assertEquals("Pixel pushers", api.get("/api/hackathons/" + h + "/board?q=pixel", v.token()).body().get("items").get(0).get("name").asText());
        assertEquals(0, api.get("/api/hackathons/" + h + "/board?q=%25", v.token()).body().get("items").size(), "'%' is not a wildcard");
    }

    @Test
    void myTeamEndpointReturnsTheTeamOrNoContent() {
        UUID h = newHackathon(2, 5);
        User leader = newUser();
        assertEquals(204, api.get("/api/hackathons/" + h + "/my-team", leader.token()).status());
        UUID team = team(leader, h, 5);
        Resp r = api.get("/api/hackathons/" + h + "/my-team", leader.token());
        assertEquals(200, r.status());
        assertEquals(team.toString(), r.body().get("id").asText());
    }
}
