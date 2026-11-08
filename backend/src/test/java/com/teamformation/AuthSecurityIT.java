package com.teamformation;

import static org.junit.jupiter.api.Assertions.*;

import com.teamformation.Api.Resp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuthSecurityIT extends AbstractIT {

    private Map<String, Object> reg(String email) {
        return Map.of("email", email, "password", "correct-horse-1", "displayName", "Ada", "utcOffsetMin", 330,
                "skills", List.of("Java", " java ", "React"), "interests", List.of("fintech"));
    }

    @Test
    void registerThenLoginThenReadProfile() {
        String email = "Ada-" + UUID.randomUUID() + "@Example.com";
        Resp r = api.post("/api/auth/register", null, reg(email));
        assertEquals(201, r.status());
        String token = r.body().get("token").asText();

        Resp login = api.post("/api/auth/login", null, Map.of("email", email.toUpperCase(), "password", "correct-horse-1"));
        assertEquals(200, login.status());

        Resp me = api.get("/api/users/me", token);
        assertEquals(200, me.status());
        assertEquals(email.toLowerCase(), me.body().get("email").asText());
        assertEquals(2, me.body().get("skills").size(), "skills are normalised and de-duplicated");
        assertFalse(me.body().toString().contains("password"), "no password material in responses");
    }

    @Test
    void duplicateEmailIsRejected() {
        String email = UUID.randomUUID() + "@example.com";
        assertEquals(201, api.post("/api/auth/register", null, reg(email)).status());
        Resp dup = api.post("/api/auth/register", null, reg(email.toUpperCase()));
        assertEquals(409, dup.status());
        assertEquals("EMAIL_TAKEN", dup.code());
    }

    @Test
    void invalidInputIsRejectedWithFieldErrors() {
        Resp r = api.post("/api/auth/register", null,
                Map.of("email", "not-an-email", "password", "short", "displayName", "A"));
        assertEquals(400, r.status());
        assertEquals("VALIDATION_FAILED", r.code());
        assertTrue(r.body().get("fields").has("email"));
        assertTrue(r.body().get("fields").has("password"));
    }

    @Test
    void wrongPasswordAndUnknownEmailLookIdentical() {
        String email = UUID.randomUUID() + "@example.com";
        api.post("/api/auth/register", null, reg(email));
        Resp wrong = api.post("/api/auth/login", null, Map.of("email", email, "password", "totally-wrong-1"));
        Resp unknown = api.post("/api/auth/login", null, Map.of("email", "nobody-" + email, "password", "totally-wrong-1"));
        assertEquals(401, wrong.status());
        assertEquals(401, unknown.status());
        assertEquals(wrong.body().get("message").asText(), unknown.body().get("message").asText());
    }

    @Test
    void protectedEndpointsRejectMissingBadAndTamperedTokens() {
        assertEquals(401, api.get("/api/users/me", null).status());
        assertEquals(401, api.get("/api/users/me", "garbage.token.value").status());
        User u = newUser();
        String tampered = u.token().substring(0, u.token().length() - 3) + (u.token().endsWith("AAA") ? "BBB" : "AAA");
        assertEquals(401, api.get("/api/users/me", tampered).status());
        assertEquals(200, api.get("/api/users/me", u.token()).status());
    }

    @Test
    void healthEndpointAndApiDocsArePublic() {
        assertEquals(200, api.get("/actuator/health", null).status());
        assertEquals(200, api.get("/v3/api-docs", null).status());
    }
}
