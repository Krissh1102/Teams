package com.teamformation.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.teamformation.common.ApiException;
import com.teamformation.common.Db;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotency-Key support.
 *
 * The key row is inserted in the SAME transaction as the business operation. A concurrent request
 * carrying the same key blocks on the primary key until the first one commits, then finds the stored
 * response and replays it. If the first one rolls back (business error), the key disappears with it,
 * so a retry simply executes again. The unique constraints on team_members are a separate, second net.
 */
@Service
public class IdempotencyService {
    public record Outcome(int status, Object body, boolean replayed) {
        public static Outcome ok(int status, Object body) { return new Outcome(status, body, false); }
    }

    private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9._:-]{1,100}$");

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public IdempotencyService(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }

    @Transactional
    public Outcome run(UUID userId, String key, String operation, String fingerprint, Supplier<Outcome> action) {
        if (key == null || key.isBlank()) return action.get();
        if (!KEY.matcher(key).matches())
            throw ApiException.badRequest("INVALID_IDEMPOTENCY_KEY", "Idempotency-Key must be 1-100 chars: letters, digits . _ : -");

        String hash = Db.sha256Hex(operation + "|" + fingerprint);
        int inserted = jdbc.update(
                "INSERT INTO idempotency_keys(user_id, idem_key, operation, request_hash) VALUES (?,?,?,?) ON CONFLICT DO NOTHING",
                userId, key, operation, hash);

        if (inserted == 0) {   // seen before: the other transaction has committed (we waited on the PK)
            List<Object[]> rows = jdbc.query(
                    "SELECT request_hash, response_code, response_body::text FROM idempotency_keys WHERE user_id=? AND idem_key=?",
                    (rs, i) -> new Object[]{rs.getString(1), (Integer) rs.getObject(2), rs.getString(3)}, userId, key);
            Object[] row = rows.get(0);
            if (!hash.equals(((String) row[0]).trim()))
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED",
                        "This Idempotency-Key was already used for a different request");
            if (row[1] == null) throw ApiException.conflict("IN_PROGRESS", "A request with this key is still in progress");
            try {
                JsonNode body = row[2] == null ? null : mapper.readTree((String) row[2]);
                return new Outcome((Integer) row[1], body, true);
            } catch (JsonProcessingException e) { throw new IllegalStateException(e); }
        }

        Outcome out = action.get();
        try {
            jdbc.update("UPDATE idempotency_keys SET response_code=?, response_body=?::jsonb WHERE user_id=? AND idem_key=?",
                    out.status(), out.body() == null ? null : mapper.writeValueAsString(out.body()), userId, key);
        } catch (JsonProcessingException e) { throw new IllegalStateException(e); }
        return out;
    }
}
