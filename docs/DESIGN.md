# Design doc — Race-proof team formation

## Data model
`users` (+ `user_skills`) · `hackathons` (min/max team size) · `teams` (leader, `max_size`, denormalised `member_count`, `status` FORMING/READY) ·
`team_members` (PK `(team_id,user_id)`, **`UNIQUE(user_id, hackathon_id)`**, composite FK `(team_id, hackathon_id) → teams` so the copied `hackathon_id` can never drift) ·
`team_invites` (SHA-256 `code_hash`, expiry, revoked) · `join_requests` (partial unique index: one PENDING per team+user) ·
`idempotency_keys` (PK `(user_id, key)`, request hash, stored response) · `team_audit_logs` (append-only via trigger, no FK so history outlives a deleted team).
`CHECK (member_count <= max_size)` is a second capacity net beneath the lock. A partial index `(hackathon_id, created_at DESC, id) WHERE member_count < max_size` serves the board.

## How races are prevented
1. **One lock per team.** Every membership change (join, accept, leave, remove, transfer) starts with `SELECT … FOR UPDATE` on the team row and locks nothing before it: a single lock order, so no deadlocks. Under READ COMMITTED a waiter re-reads the freshly committed row, so the 50th joiner sees `member_count = max_size` and is rejected with 409.
2. **Constraints, not hope.** A person racing across *different* teams holds different team locks, so only `uq_user_hackathon` can stop them; the violation rolls the transaction back and the global handler maps it to 409 `ALREADY_IN_TEAM`. `chk_member_count` and `uq_pending_request` are mapped the same way.
3. **Idempotency inside the transaction.** `INSERT … ON CONFLICT DO NOTHING` on `(user_id, key)` runs in the same transaction as the operation. A concurrent duplicate blocks on the primary key until the first commits, then replays the stored response (header `Idempotent-Replay`). A failed business operation rolls its key back, so a retry re-executes. Reusing a key for a different request → 422.
4. **Events after commit.** `@TransactionalEventListener(AFTER_COMMIT)` pushes SSE *notifications*; clients refetch over authenticated REST, and refetch again after a reconnect. Rolled-back work is never announced.

## Security
JWT (HS256, ≥32-byte secret), BCrypt, stateless. Leader-only actions are checked against the *locked, fresh* team row. Invite codes: 16 random bytes, stored only as SHA-256, 48 h expiry, one live code per team, identical error for unknown/expired/wrong-team, fixed-window rate limits per user and per IP on join plus per IP on auth. Bean Validation on every body; unknown/malformed ids are 400/404, never 500. Login does equal work for unknown emails.

## Trade-offs
- **JdbcTemplate over JPA** for the critical paths: the locking SQL is explicit and reviewable.
- **Pessimistic locking over optimistic/serializable**: a hot team is exactly the high-contention case where waiting beats retry storms; the lock is held for a few statements.
- **`member_count` denormalised**: cheaper board and a second invariant, at the cost of keeping it in step (tested; `scripts/invariants.sql` checks drift).
- **SSE over WebSocket**: one-way, proxy-friendly, auto-reconnect. Events carry no data, so the stream needs no auth.
- **In-memory rate limiter and SSE hub**: correct for one instance; scaling out needs a shared store and a LISTEN/NOTIFY or broker fan-out.
- **Board reads are not cached**: freshness over raw speed; the query costs ~0.14 ms.
- `min_team_size` drives READY/FORMING status rather than blocking leaves.

## With one more week
Transactional outbox + LISTEN/NOTIFY fan-out; Redis (or bucket4j-jdbc) rate limits; keyset pagination for the board; chaos test (kill -9 the API mid-join, then `invariants.sql`); Micrometer/OpenTelemetry tracing around lock-wait time; refresh tokens and email verification; Playwright keyboard-only and axe accessibility tests in CI; leader-election tie-break options and team merge.
