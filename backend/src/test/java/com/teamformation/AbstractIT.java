package com.teamformation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.teamformation.Api.Resp;
import com.teamformation.common.Db;
import com.teamformation.config.JwtService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Real PostgreSQL (Testcontainers), real HTTP, real Spring Security chain. No H2: locking behaviour
 * is only meaningful on the real database. One container is shared by all test classes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class AbstractIT {
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");
    static { PG.start(); }

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
        r.add("spring.datasource.hikari.maximum-pool-size", () -> "40");
    }

    @LocalServerPort protected int port;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected JwtService jwt;
    protected Api api;

    @BeforeEach void initApi() { api = new Api(port); }

    public record User(UUID id, String token) {}

    protected User newUser() { return newUser("User", 0, List.of(), List.of()); }

    protected User newUser(String name, int offsetMin, List<String> skills, List<String> interests) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users(id, email, password_hash, display_name, utc_offset_min, interests) VALUES (?,?,?,?,?,?)",
                id, id + "@test.io", "x", name, offsetMin, Db.textArray(interests));
        for (String s : skills) jdbc.update("INSERT INTO user_skills(user_id, skill) VALUES (?,?)", id, s);
        return new User(id, jwt.issue(id));
    }

    protected List<User> newUsers(int n) {
        List<User> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(newUser());
        return out;
    }

    protected UUID newHackathon(int min, int max) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO hackathons(id, name, min_team_size, max_team_size) VALUES (?,?,?,?)", id, "H-" + id, min, max);
        return id;
    }

    protected UUID team(User leader, UUID hackathon, int max) {
        Resp r = api.post("/api/teams", leader.token(),
                Map.of("hackathonId", hackathon, "name", "Team " + UUID.randomUUID().toString().substring(0, 6), "maxSize", max));
        assertEquals(201, r.status(), r.body().toString());
        return UUID.fromString(r.body().get("team").get("id").asText());
    }

    protected String invite(User leader, UUID team) {
        Resp r = api.post("/api/teams/" + team + "/invites", leader.token(), null);
        assertEquals(201, r.status(), r.body().toString());
        return r.body().get("code").asText();
    }

    protected Resp join(User u, UUID team, String code) {
        return api.post("/api/teams/" + team + "/join", u.token(), Map.of("inviteCode", code));
    }

    protected void joinOk(User u, UUID team, String code) { assertEquals(200, join(u, team, code).status()); }

    protected int rows(UUID team) {
        return jdbc.queryForObject("SELECT count(*) FROM team_members WHERE team_id = ?", Integer.class, team);
    }

    protected int countColumn(UUID team) {
        return jdbc.queryForObject("SELECT member_count FROM teams WHERE id = ?", Integer.class, team);
    }

    /** Fires n actions at the same instant (barrier-synchronised threads) and returns every response. */
    protected List<Resp> race(int n, IntFunction<Resp> action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CyclicBarrier barrier = new CyclicBarrier(n);
        try {
            List<Future<Resp>> fs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                final int idx = i;
                fs.add(pool.submit(() -> { barrier.await(); return action.apply(idx); }));
            }
            List<Resp> out = new ArrayList<>();
            for (Future<Resp> f : fs) out.add(f.get());
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    protected static long count(List<Resp> rs, int status) { return rs.stream().filter(r -> r.status() == status).count(); }
}
