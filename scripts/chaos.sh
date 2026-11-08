#!/usr/bin/env bash
# CHAOS TEST: kill -9 the API in the middle of a join storm, restart it, retry everything, prove nothing is corrupted.
#
#   docker compose -f docker-compose.yml -f docker-compose.chaos.yml up -d --build   (the script does this if the API is down)
#   ./scripts/chaos.sh
#
# What it proves:
#   1. After SIGKILL mid-flight, no team is over capacity, no counter drifts, nobody is on two teams, no leaderless/empty team.
#   2. Joins are ATOMIC with their idempotency record: members joined == stored Idempotency-Key rows (a crash can never leave
#      one without the other).
#   3. Retrying every request with the SAME Idempotency-Key after the restart is safe (replayed, never double-applied).
# Needs: docker compose, openssl, curl. Tokens are minted locally from JWT_SECRET (no login => no auth rate limit).
set -uo pipefail
cd "$(dirname "$0")/.."

API=${API:-http://localhost:8080}
SECRET=${JWT_SECRET:-change-me-change-me-change-me-change-me}      # must match docker-compose.yml
TEAMS=${TEAMS:-40}; CAP=${CAP:-3}; CONTENDERS=${CONTENDERS:-300}; ATTEMPTS=${ATTEMPTS:-3}
PARALLEL=${PARALLEL:-40}; KILL_AFTER=${KILL_AFTER:-1.5}
COMPOSE=${COMPOSE:-docker compose -f docker-compose.yml -f docker-compose.chaos.yml}
PSQL=${PSQL:-$COMPOSE exec -T db psql -q -U teamformation -d teamformation -v ON_ERROR_STOP=1}
HACK=c0c0c0c2-0000-0000-0000-000000000001
WORK=${WORK:-$(mktemp -d)}

b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }
uid()    { printf 'c0c0c0c0-0000-0000-0000-%012x' "$1"; }
tid()    { printf 'c0c0c0c1-0000-0000-0000-%012x' "$1"; }

# HS256 JWT with only a subject, exactly what the API's JwtService verifies
mint() {
  local h p s now
  now=$(date +%s)
  h=$(printf '{"alg":"HS256","typ":"JWT"}' | b64url)
  p=$(printf '{"sub":"%s","iat":%d,"exp":%d}' "$1" "$now" "$((now + 3600))" | b64url)
  s=$(printf '%s.%s' "$h" "$p" | openssl dgst -sha256 -hmac "$SECRET" -binary | b64url)
  printf '%s.%s.%s' "$h" "$p" "$s"
}

setup_data() {
  $PSQL <<SQL
DELETE FROM teams WHERE hackathon_id = '$HACK';
DELETE FROM idempotency_keys WHERE idem_key LIKE 'chaos-%';
DELETE FROM users WHERE email LIKE '%@chaos.test';
DELETE FROM hackathons WHERE id = '$HACK';
INSERT INTO hackathons(id, name, min_team_size, max_team_size, hidden) VALUES ('$HACK', 'chaos-test', 2, $CAP, TRUE);
INSERT INTO users(id, email, password_hash, display_name)
  SELECT ('c0c0c0c0-0000-0000-0000-' || lpad(to_hex(n), 12, '0'))::uuid, 'u' || n || '@chaos.test', 'x', 'Chaos ' || n
  FROM generate_series(1, $TEAMS + $CONTENDERS) n;
INSERT INTO teams(id, hackathon_id, leader_id, name, max_size, member_count, status)
  SELECT ('c0c0c0c1-0000-0000-0000-' || lpad(to_hex(t), 12, '0'))::uuid, '$HACK',
         ('c0c0c0c0-0000-0000-0000-' || lpad(to_hex(t), 12, '0'))::uuid, 'Chaos team ' || t, $CAP, 1, 'FORMING'
  FROM generate_series(1, $TEAMS) t;
INSERT INTO team_members(team_id, user_id, hackathon_id)
  SELECT ('c0c0c0c1-0000-0000-0000-' || lpad(to_hex(t), 12, '0'))::uuid,
         ('c0c0c0c0-0000-0000-0000-' || lpad(to_hex(t), 12, '0'))::uuid, '$HACK'
  FROM generate_series(1, $TEAMS) t;
INSERT INTO team_invites(team_id, code_hash, expires_at, created_by)
  SELECT ('c0c0c0c1-0000-0000-0000-' || lpad(to_hex(t), 12, '0'))::uuid,
         encode(sha256(convert_to('chaoscode-' || t || '-padpadpadpad', 'UTF8')), 'hex'), now() + interval '2 hours',
         ('c0c0c0c0-0000-0000-0000-' || lpad(to_hex(t), 12, '0'))::uuid
  FROM generate_series(1, $TEAMS) t;
SQL
}

mint_tokens() { local n; for ((n = TEAMS + 1; n <= TEAMS + CONTENDERS; n++)); do mint "$(uid "$n")" > "$WORK/tok.$n"; done; }

# every contender tries ATTEMPTS different teams; the same user/team pair always uses the same Idempotency-Key
request_list() { local n k; for ((n = TEAMS + 1; n <= TEAMS + CONTENDERS; n++)); do for ((k = 1; k <= ATTEMPTS; k++)); do echo "$n $(( (n * 7 + k * 13) % TEAMS + 1 ))"; done; done; }

join_one() {   # $1 = user number, $2 = team number; prints the HTTP status (000 = connection failed)
  curl -s -o /dev/null -w '%{http_code}\n' --max-time 6 -X POST "$API/api/teams/$(tid "$2")/join" \
    -H "Authorization: Bearer $(cat "$WORK/tok.$1")" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: chaos-u$1-t$2" -d "{\"inviteCode\":\"chaoscode-$2-padpadpadpad\"}"
}

storm() { request_list | xargs -P "$PARALLEL" -L1 bash -c 'join_one "$0" "$1"' > "$1" 2>/dev/null; }   # $1 = output file

tally() { sort "$1" | uniq -c | awk '{printf "%s x%s   ", $2, $1} END {print ""}'; }

wait_healthy() { local i; for i in $(seq 1 120); do [[ $(curl -s -o /dev/null -w '%{http_code}' "$API/actuator/health") == 200 ]] && return 0; sleep 1; done; return 1; }

audit_db() {
  echo "== 5. audit the database"
  local viol atomic filled bad=0
  # fail CLOSED: if the database cannot be queried, that is a failure, never a pass
  viol=$($PSQL -At < scripts/invariants.sql | sed '/^$/d') || { echo "   FAIL  could not query the database for invariants"; return 1; }
  if [[ -z "$viol" ]]; then echo "   PASS  invariants: 0 violations (capacity, counters, duplicates, leaders, empty teams, status)"
  else echo "   FAIL  invariants violated:"; echo "$viol" | sed 's/^/         /'; bad=1; fi

  atomic=$($PSQL -At -c "SELECT (SELECT count(*) FROM team_members WHERE hackathon_id = '$HACK') - $TEAMS
                                = (SELECT count(*) FROM idempotency_keys WHERE idem_key LIKE 'chaos-%' AND response_code = 200)") \
    || { echo "   FAIL  could not query the database for the atomicity check"; return 1; }
  if [[ "$atomic" == "t" ]]; then echo "   PASS  every committed join has exactly one stored idempotency record (atomic across the crash)"
  else echo "   FAIL  joins and idempotency records disagree"; bad=1; fi

  filled=$($PSQL -At -c "SELECT count(*) || ' members across ' || count(DISTINCT team_id) || ' teams (capacity ' || ($TEAMS * $CAP) || ')' FROM team_members WHERE hackathon_id = '$HACK'")
  echo "   info  $filled"
  return $bad
}

main() {
  for c in curl openssl; do command -v "$c" >/dev/null || { echo "missing: $c"; exit 2; }; done
  export API WORK TEAMS CAP; export -f join_one tid

  echo "== 0. make sure the API is up"
  if ! wait_healthy; then $COMPOSE up -d --build >/dev/null 2>&1; wait_healthy || { echo "API did not start"; exit 2; }; fi

  echo "== 1. fixtures: $TEAMS teams x $CAP seats, $CONTENDERS contenders, $ATTEMPTS attempts each"
  setup_data || { echo "fixture setup failed"; exit 2; }
  mint_tokens

  echo "== 2. join storm #1, and kill -9 the API ${KILL_AFTER}s in"
  storm "$WORK/storm1.codes" & local storm_pid=$!
  sleep "$KILL_AFTER"
  $COMPOSE kill -s SIGKILL backend >/dev/null 2>&1
  echo "   API killed mid-flight"
  wait "$storm_pid"
  echo "   storm #1 results: $(tally "$WORK/storm1.codes")   (000 = connection refused after the kill)"

  echo "== 3. restart the API (Flyway + connection pool come back from scratch)"
  $COMPOSE up -d backend >/dev/null 2>&1
  wait_healthy || { echo "FAIL: API did not come back"; exit 1; }
  echo "   API healthy again"

  echo "== 4. retry EVERYTHING with the same Idempotency-Keys"
  storm "$WORK/storm2.codes"
  echo "   storm #2 results: $(tally "$WORK/storm2.codes")"

  audit_db || bad=1

  if (( bad )); then echo "CHAOS TEST FAILED"; exit 1; fi
  echo "CHAOS TEST PASSED: killed mid-join, restarted, retried, nothing corrupted"
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then main "$@"; fi
