package com.teamformation;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.teamformation.Api.Resp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

class RecommendationAuditIT extends AbstractIT {

    private UUID backendTeam(User leader, UUID h) {
        Resp r = api.post("/api/teams", leader.token(), Map.of("hackathonId", h, "name", "Backend crew",
                "lookingFor", List.of("react", "ui"), "maxSize", 5));
        assertEquals(201, r.status());
        return UUID.fromString(r.body().get("team").get("id").asText());
    }

    private int rank(JsonNode suggestions, User u) {
        for (int i = 0; i < suggestions.size(); i++)
            if (suggestions.get(i).get("userId").asText().equals(u.id().toString())) return i;
        return -1;
    }

    @Test
    void suggestionsAreRankedByComplementarySkillsAndTimeZoneAndExplained() {
        UUID h = newHackathon(2, 5);
        User leader = newUser("Lead", 330, List.of("java", "postgres"), List.of("fintech"));
        UUID team = backendTeam(leader, h);
        User perfect = newUser("Perfect", 330, List.of("react", "ui"), List.of("fintech"));
        User skillsOnly = newUser("FarAway", -480, List.of("react", "ui"), List.of());
        User redundant = newUser("Redundant", 330, List.of("java", "postgres"), List.of());

        Resp r = api.get("/api/teams/" + team + "/suggestions?limit=50", leader.token());
        assertEquals(200, r.status());
        JsonNode s = r.body().get("suggestions");
        assertTrue(rank(s, perfect) >= 0 && rank(s, perfect) < rank(s, skillsOnly), "same time zone beats 13h away");
        assertTrue(rank(s, skillsOnly) < rank(s, redundant), "complementary skills beat duplicated skills");
        assertEquals(-1, rank(s, leader), "team members are never suggested");
        assertTrue(s.get(rank(s, perfect)).get("score").asDouble() > 80);
        assertFalse(s.get(0).get("reasons").isEmpty(), "every suggestion explains itself");
        assertEquals(0.5, r.body().get("weights").get("skills").asDouble());
        assertEquals(0.3, r.body().get("weights").get("timezone").asDouble());
        assertEquals(0.2, r.body().get("weights").get("interests").asDouble());
        assertTrue(r.body().get("explanation").asText().contains("timezone"));
    }

    @Test
    void peopleAlreadyOnATeamAreNotSuggestedAndOutsidersCannotAsk() {
        UUID h = newHackathon(2, 5);
        User leader = newUser();
        UUID team = backendTeam(leader, h);
        User taken = newUser("Taken", 0, List.of("react"), List.of());
        User other = newUser();
        UUID otherTeam = team(other, h, 5);
        joinOk(taken, otherTeam, invite(other, otherTeam));

        JsonNode s = api.get("/api/teams/" + team + "/suggestions?limit=50", leader.token()).body().get("suggestions");
        assertEquals(-1, rank(s, taken));
        assertEquals(403, api.get("/api/teams/" + team + "/suggestions", newUser().token()).status());
    }

    @Test
    void auditLogRecordsWhoDidWhatInOrder() {
        UUID h = newHackathon(2, 5);
        User leader = newUser("Lena", 0, List.of(), List.of());
        User member = newUser("Max", 0, List.of(), List.of());
        UUID team = team(leader, h, 5);
        joinOk(member, team, invite(leader, team));
        assertEquals(200, api.delete("/api/teams/" + team + "/leave", member.token()).status());

        Resp r = api.get("/api/teams/" + team + "/audit", leader.token());
        assertEquals(200, r.status());
        List<String> actions = new ArrayList<>();
        r.body().forEach(e -> actions.add(e.get("action").asText()));
        assertEquals(List.of("TEAM_CREATED", "INVITE_CREATED", "MEMBER_JOINED", "MEMBER_LEFT"), actions);
        assertEquals("Lena", r.body().get(0).get("actorName").asText());
        assertEquals("Max", r.body().get(2).get("actorName").asText());
        assertNotNull(r.body().get(0).get("at"));
        assertEquals(403, api.get("/api/teams/" + team + "/audit", newUser().token()).status(), "members only");
    }

    @Test
    void auditLogIsAppendOnlyEvenForTheDatabaseUserOfTheApp() {
        UUID h = newHackathon(2, 5);
        UUID team = team(newUser(), h, 5);
        assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE team_audit_logs SET action = 'FORGED' WHERE team_id = ?", team));
        assertThrows(DataAccessException.class, () -> jdbc.update("DELETE FROM team_audit_logs WHERE team_id = ?", team));
    }
}
