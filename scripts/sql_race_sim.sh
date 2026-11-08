#!/bin/bash
# SQL-level proof of the concurrency design, no application needed. Needs a PostgreSQL you can create databases in
# (set PGHOST / PGUSER / PGPASSWORD as usual). It creates a scratch database "race_sim", applies the real migrations,
# and fires 50 truly simultaneous sessions (released together by an advisory-lock barrier) at a team with ONE seat left:
#   SAFE     lock the team row first         -> 1 joined, 49 cleanly rejected
#   NO_LOCK  check-then-act, constraint only -> 1 joined, 49 raw constraint errors
#   NAIVE    no lock, no counter             -> overbooked, counter drift (what the design prevents)
HERE="$(cd "$(dirname "$0")" && pwd)"
dropdb --if-exists race_sim 2>/dev/null; createdb race_sim || exit 1
for f in "$HERE"/../backend/src/main/resources/db/migration/V*.sql; do psql -q -d race_sim -v ON_ERROR_STOP=1 -f "$f" || exit 1; done
P="psql -q -d race_sim -v ON_ERROR_STOP=1"
T=11111111-1111-1111-1111-111111111111
H=00000000-0000-0000-0000-000000000001
fixture() { $P >/dev/null 2>&1 <<EOF
TRUNCATE team_members, teams, users CASCADE;
INSERT INTO users(id,email,password_hash,display_name) SELECT gen_random_uuid(),'a'||lpad(g::text,2,'0')||'@t.io','x','A'||g FROM generate_series(1,54) g;
INSERT INTO teams(id,hackathon_id,leader_id,name,max_size,member_count,status) SELECT '$T','$H',(SELECT id FROM users WHERE email='a01@t.io'),'T',5,4,'READY';
INSERT INTO team_members(team_id,user_id,hackathon_id) SELECT '$T',id,'$H' FROM users WHERE email IN ('a01@t.io','a02@t.io','a03@t.io','a04@t.io');
EOF
}
BARRIER="SELECT pg_advisory_lock_shared(42); SELECT pg_advisory_unlock_shared(42);"
cat > /tmp/safe.sql <<EOF
\set ON_ERROR_STOP off
$BARRIER
BEGIN;
SELECT member_count, max_size FROM teams WHERE id='$T' FOR UPDATE \gset
SELECT (:member_count < :max_size) AS ok \gset
\if :ok
  INSERT INTO team_members(team_id,user_id,hackathon_id) VALUES ('$T', :'uid','$H');
  UPDATE teams SET member_count = member_count + 1 WHERE id='$T';
  COMMIT;
  \echo RESULT=JOINED
\else
  ROLLBACK;
  \echo RESULT=REJECTED_FULL
\endif
EOF
cat > /tmp/nolock.sql <<EOF
\set ON_ERROR_STOP off
$BARRIER
BEGIN;
SELECT member_count, max_size FROM teams WHERE id='$T' \gset
SELECT (:member_count < :max_size) AS ok \gset
\if :ok
  SELECT pg_sleep(0.01);
  INSERT INTO team_members(team_id,user_id,hackathon_id) VALUES ('$T', :'uid','$H');
  UPDATE teams SET member_count = member_count + 1 WHERE id='$T';
  COMMIT;
\else
  ROLLBACK;
  \echo RESULT=REJECTED_FULL
\endif
EOF
cat > /tmp/naive.sql <<EOF
\set ON_ERROR_STOP off
$BARRIER
BEGIN;
SELECT count(*) AS n FROM team_members WHERE team_id='$T' \gset
SELECT (:n < 5) AS ok \gset
\if :ok
  SELECT pg_sleep(0.01);
  INSERT INTO team_members(team_id,user_id,hackathon_id) VALUES ('$T', :'uid','$H');
  COMMIT;
  \echo RESULT=JOINED
\else
  ROLLBACK;
  \echo RESULT=REJECTED_FULL
\endif
EOF
run() {
  rm -rf /tmp/o; mkdir /tmp/o
  $P -Atc "SELECT id FROM users WHERE email >= 'a05@t.io' ORDER BY email LIMIT 50" > /tmp/uids.txt
  psql -q -d race_sim -c "SELECT pg_advisory_lock(42); SELECT pg_sleep(6);" >/dev/null 2>&1 &
  sleep 0.8
  i=0
  while read -r uid; do i=$((i+1)); (psql -q -d race_sim -v uid=$uid -f $1 > /tmp/o/$i 2>&1) & done < /tmp/uids.txt
  wait
  echo "   sessions=$i  clean_REJECTED_FULL=$(cat /tmp/o/* | grep -c REJECTED_FULL)  chk_member_count_errors=$(cat /tmp/o/* | grep -c chk_member_count)"
  $P -Atc "SELECT '   FINAL rows='||(SELECT count(*) FROM team_members)||' counter='||member_count||' capacity=5 overbooked_by='||greatest(0,(SELECT count(*) FROM team_members)-5) FROM teams"
}
echo "== SAFE (FOR UPDATE), barrier-synchronised"; fixture; run /tmp/safe.sql
echo "== NO_LOCK (CHECK constraint only)"; fixture; run /tmp/nolock.sql
echo "== NAIVE (no lock, no counter)"; fixture; run /tmp/naive.sql
echo "-- invariants after NAIVE:"; $P -f "$HERE/invariants.sql"
