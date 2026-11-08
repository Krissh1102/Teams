package com.teamformation.arena;

import com.teamformation.common.ApiException;
import com.teamformation.common.Db;
import com.teamformation.team.TeamService;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import org.postgresql.util.PSQLException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Race Arena: a live, repeatable proof of the concurrency design. It builds a throwaway team with ONE
 * seat left, releases N contenders at the same instant through a barrier, and then audits the result.
 *
 *  SAFE     the real production join path (team-row lock + capacity check + unique index).
 *  NO_LOCK  what you get without the lock: check-then-act races, rescued only by the CHECK constraint.
 *  NAIVE    no lock and no counter: classic overbooking. Exists only to show what the design prevents.
 *
 * Only active when app.arena.enabled=true. Touches nothing but its own hidden fixture rows, runs one
 * race at a time, and removes everything afterwards.
 */
@Service
@ConditionalOnProperty(name = "app.arena.enabled", havingValue = "true")
public class ArenaService {
    public enum Mode { SAFE, NO_LOCK, NAIVE }

    public record Attempt(int contender, String outcome, String code, long durationMs, long finishedAtMs) {}
    public record Check(String name, boolean pass, String detail) {}
    public record Report(Mode mode, int capacity, int initialMembers, int contenders, int joined, int rejected, int errors,
                         int finalMembers, int memberCounter, int duplicates, int capacityViolations, long totalMs,
                         boolean consistent, String verdict, List<Check> checks, List<Attempt> attempts) {}

    private record Fixture(UUID hackathonId, UUID teamId, String code, int capacity, List<UUID> contenders) {}

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final TeamService teams;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public ArenaService(JdbcTemplate jdbc, PlatformTransactionManager tm, TeamService teams) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(tm);
        this.teams = teams;
    }

    public Report run(Mode mode, int contenders, int capacity) {
        if (contenders < 2 || contenders > 100) throw ApiException.badRequest("INVALID_ARENA", "contenders must be 2-100");
        if (capacity < 2 || capacity > 10) throw ApiException.badRequest("INVALID_ARENA", "capacity must be 2-10");
        if (!running.compareAndSet(false, true))
            throw ApiException.conflict("ARENA_BUSY", "A race is already running, try again in a few seconds");
        try {
            cleanup();                                   // also removes leftovers of a crashed earlier run
            Fixture f = setup(contenders, capacity);
            try {
                return race(mode, f, contenders);
            } finally {
                cleanup();
            }
        } finally {
            running.set(false);
        }
    }

    // ------------------------------------------------------------------ fixture

    private Fixture setup(int contenders, int capacity) {
        UUID hackathon = UUID.randomUUID(), team = UUID.randomUUID();
        String run = hackathon.toString().substring(0, 8);
        int initial = capacity - 1;                      // exactly one seat left
        byte[] raw = new byte[16];
        RANDOM.nextBytes(raw);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        List<UUID> members = new ArrayList<>();
        List<UUID> contenderIds = new ArrayList<>();

        tx.executeWithoutResult(s -> {
            jdbc.update("INSERT INTO hackathons(id, name, min_team_size, max_team_size, hidden) VALUES (?,?,?,?,TRUE)",
                    hackathon, "arena-" + run, 2, capacity);
            for (int i = 0; i < initial; i++) members.add(newUser(run, "member" + i));
            jdbc.update("INSERT INTO teams(id, hackathon_id, leader_id, name, max_size, member_count, status) VALUES (?,?,?,?,?,?,?)",
                    team, hackathon, members.get(0), "Last Seat Team", capacity, initial, initial >= 2 ? "READY" : "FORMING");
            for (UUID m : members)
                jdbc.update("INSERT INTO team_members(team_id, user_id, hackathon_id) VALUES (?,?,?)", team, m, hackathon);
            jdbc.update("INSERT INTO team_invites(team_id, code_hash, expires_at, created_by) VALUES (?,?,?,?)",
                    team, Db.sha256Hex(code), Timestamp.from(Instant.now().plus(1, ChronoUnit.HOURS)), members.get(0));
            for (int i = 0; i < contenders; i++) contenderIds.add(newUser(run, "contender" + i));
        });
        return new Fixture(hackathon, team, code, capacity, contenderIds);
    }

    private UUID newUser(String run, String tag) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users(id, email, password_hash, display_name) VALUES (?,?,?,?)",
                id, "arena-" + run + "-" + tag + "@arena.test", "x", "Arena " + tag);
        return id;
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            jdbc.update("DELETE FROM teams WHERE hackathon_id IN (SELECT id FROM hackathons WHERE hidden AND name LIKE 'arena-%')");
            jdbc.update("DELETE FROM users WHERE email LIKE '%@arena.test'");
            jdbc.update("DELETE FROM hackathons WHERE hidden AND name LIKE 'arena-%'");
        });
    }

    // ------------------------------------------------------------------ the race

    private Report race(Mode mode, Fixture f, int n) {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CyclicBarrier barrier = new CyclicBarrier(n);
        long[] starts = new long[n], ends = new long[n];
        String[] outcomes = new String[n], codes = new String[n];
        List<Future<Void>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < n; i++) {
                final int idx = i;
                futures.add(pool.submit((Callable<Void>) () -> {
                    try { barrier.await(); }
                    catch (Exception e) { outcomes[idx] = "ERROR"; codes[idx] = "BARRIER"; return null; }
                    starts[idx] = System.nanoTime();
                    try {
                        attempt(mode, f, f.contenders().get(idx));
                        outcomes[idx] = "JOINED"; codes[idx] = "OK";
                    } catch (ApiException e) {
                        outcomes[idx] = "REJECTED"; codes[idx] = e.code();
                    } catch (DataAccessException e) {
                        outcomes[idx] = "ERROR"; codes[idx] = constraintOrClass(e);
                    } catch (RuntimeException e) {
                        outcomes[idx] = "ERROR"; codes[idx] = e.getClass().getSimpleName();
                    }
                    ends[idx] = System.nanoTime();
                    return null;
                }));
            }
            for (Future<Void> fu : futures) fu.get();
        } catch (Exception e) {
            throw new IllegalStateException("Arena race failed", e);
        } finally {
            pool.shutdownNow();
        }

        long minStart = Long.MAX_VALUE, maxEnd = 0;
        for (int i = 0; i < n; i++) {
            if (starts[i] != 0) minStart = Math.min(minStart, starts[i]);
            maxEnd = Math.max(maxEnd, ends[i]);
        }
        if (minStart == Long.MAX_VALUE) minStart = maxEnd;
        List<Attempt> attempts = new ArrayList<>();
        int joined = 0, rejected = 0, errors = 0;
        for (int i = 0; i < n; i++) {
            String o = outcomes[i] == null ? "ERROR" : outcomes[i];
            if (o.equals("JOINED")) joined++; else if (o.equals("REJECTED")) rejected++; else errors++;
            long dur = ends[i] == 0 ? 0 : (ends[i] - starts[i]) / 1_000_000;
            long fin = ends[i] == 0 ? 0 : (ends[i] - minStart) / 1_000_000;
            attempts.add(new Attempt(i + 1, o, codes[i] == null ? "UNKNOWN" : codes[i], dur, fin));
        }
        attempts.sort(Comparator.comparingLong(Attempt::finishedAtMs));

        // ---- audit the end state straight from the database
        int rows = jdbc.queryForObject("SELECT count(*) FROM team_members WHERE team_id = ?", Integer.class, f.teamId());
        int counter = jdbc.queryForObject("SELECT member_count FROM teams WHERE id = ?", Integer.class, f.teamId());
        int distinct = jdbc.queryForObject("SELECT count(DISTINCT user_id) FROM team_members WHERE team_id = ?", Integer.class, f.teamId());
        int duplicates = rows - distinct;
        int violations = Math.max(0, rows - f.capacity());

        List<Check> checks = List.of(
                new Check("Never more members than seats", rows <= f.capacity(), rows + " members for " + f.capacity() + " seats"),
                new Check("member_count matches the membership rows", counter == rows, "counter " + counter + ", rows " + rows),
                new Check("Nobody is on the team twice", duplicates == 0, duplicates + " duplicates"),
                new Check("Exactly one person took the last seat", joined == 1, joined + " requests succeeded"));
        boolean consistent = checks.get(0).pass() && checks.get(1).pass() && checks.get(2).pass();

        String verdict;
        if (!consistent) verdict = "CORRUPTED: " + violations + " people were given seats that do not exist"
                + (counter != rows ? " and the counter is wrong." : ".");
        else if (errors > 0) verdict = "Data intact, but " + errors + " requests died with raw database errors. "
                + "The constraints were the only thing protecting you.";
        else if (joined == 1) verdict = "Exactly one winner, everyone else cleanly rejected. Nothing corrupted.";
        else verdict = "State is consistent.";

        return new Report(mode, f.capacity(), f.capacity() - 1, n, joined, rejected, errors, rows, counter,
                duplicates, violations, (maxEnd - minStart) / 1_000_000, consistent, verdict, checks, attempts);
    }

    private void attempt(Mode mode, Fixture f, UUID user) {
        switch (mode) {
            case SAFE -> teams.joinWithInvite(user, f.teamId(), f.code());
            case NO_LOCK -> tx.executeWithoutResult(s -> {
                // check-then-act with NO row lock: everyone sees "4 of 5" and goes ahead
                int[] r = jdbc.queryForObject("SELECT member_count, max_size FROM teams WHERE id = ?",
                        (rs, i) -> new int[]{rs.getInt(1), rs.getInt(2)}, f.teamId());
                if (r[0] >= r[1]) throw ApiException.conflict("TEAM_FULL", "This team is full");
                pause();
                jdbc.update("INSERT INTO team_members(team_id, user_id, hackathon_id) VALUES (?,?,?)", f.teamId(), user, f.hackathonId());
                jdbc.update("UPDATE teams SET member_count = member_count + 1 WHERE id = ?", f.teamId());   // CHECK constraint fires here
            });
            case NAIVE -> tx.executeWithoutResult(s -> {
                // no lock, no counter: just "count the rows, then insert"
                Integer count = jdbc.queryForObject("SELECT count(*) FROM team_members WHERE team_id = ?", Integer.class, f.teamId());
                if (count != null && count >= f.capacity()) throw ApiException.conflict("TEAM_FULL", "This team is full");
                pause();
                jdbc.update("INSERT INTO team_members(team_id, user_id, hackathon_id) VALUES (?,?,?)", f.teamId(), user, f.hackathonId());
            });
        }
    }

    /** Widens the check-then-act window the way real network and GC delays do. */
    private static void pause() {
        try { Thread.sleep(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static String constraintOrClass(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof PSQLException p && p.getServerErrorMessage() != null && p.getServerErrorMessage().getConstraint() != null)
                return p.getServerErrorMessage().getConstraint();
        }
        return e.getClass().getSimpleName();
    }
}
