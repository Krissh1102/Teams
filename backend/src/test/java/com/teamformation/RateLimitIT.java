package com.teamformation;

import static org.junit.jupiter.api.Assertions.*;

import com.teamformation.Api.Resp;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {
        "app.rate-limit.auth-per-minute-per-ip=3",
        "app.rate-limit.join-per-minute-per-user=2"})
class RateLimitIT extends AbstractIT {

    @Test
    void loginAttemptsArePerIpRateLimited() {
        Map<String, String> body = Map.of("email", "x@example.com", "password", "whatever-123");
        for (int i = 0; i < 3; i++) assertEquals(401, api.post("/api/auth/login", null, body).status());
        Resp limited = api.post("/api/auth/login", null, body);
        assertEquals(429, limited.status());
        assertEquals("RATE_LIMITED", limited.code());
        assertTrue(limited.headers().firstValue("Retry-After").isPresent());
    }

    @Test
    void inviteGuessingIsRateLimitedPerUser() {
        UUID h = newHackathon(2, 5);
        User leader = newUser();
        UUID team = team(leader, h, 5);
        User guesser = newUser();
        assertEquals(400, join(guesser, team, "AAAAAAAAAAAAAAAAAAAAAA").status());
        assertEquals(400, join(guesser, team, "BBBBBBBBBBBBBBBBBBBBBB").status());
        assertEquals(429, join(guesser, team, "CCCCCCCCCCCCCCCCCCCCCC").status());
        // another user is unaffected by the first user's limit
        assertEquals(400, join(newUser(), team, "DDDDDDDDDDDDDDDDDDDDDD").status());
    }
}
