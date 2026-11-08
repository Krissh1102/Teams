-- Seed: 1 hackathon, 10,000 users, 2,000 teams (sizes 1..5, so ~60% have an open seat).
-- Idempotent-ish: run against a fresh database after the app has applied its Flyway migrations.
-- All seeded users share the password "password123".
--   psql "$DATABASE_URL" -f loadtest/seed.sql
CREATE EXTENSION IF NOT EXISTS pgcrypto;

BEGIN;
DELETE FROM team_audit_logs WHERE team_id IN (SELECT id FROM teams WHERE hackathon_id = '00000000-0000-0000-0000-0000000000a1');
DELETE FROM teams WHERE hackathon_id = '00000000-0000-0000-0000-0000000000a1';
DELETE FROM users WHERE email LIKE '%@seed.test';
DELETE FROM hackathons WHERE id = '00000000-0000-0000-0000-0000000000a1';

INSERT INTO hackathons(id, name, min_team_size, max_team_size)
VALUES ('00000000-0000-0000-0000-0000000000a1', 'Load Test Hackathon', 2, 5);

-- 10,000 users with deterministic pseudo-random offsets, interests and 2-4 skills
WITH pw AS (SELECT crypt('password123', gen_salt('bf', 10)) AS h),
     skills AS (SELECT ARRAY['java','react','python','ui','ux','ml','devops','go','rust','postgres','mobile','data'] AS s),
     topics AS (SELECT ARRAY['fintech','health','edtech','climate','gaming','social','ai','security'] AS t)
INSERT INTO users(id, email, password_hash, display_name, utc_offset_min, interests)
SELECT ('00000000-0000-0000-0000-' || lpad(to_hex(n), 12, '0'))::uuid,
       'user' || n || '@seed.test', pw.h, 'Seed User ' || n,
       ((n * 7) % 25 - 12) * 60,
       ARRAY[topics.t[1 + n % 8], topics.t[1 + (n * 3) % 8]]
FROM generate_series(1, 10000) n, pw, topics;

WITH skills AS (SELECT ARRAY['java','react','python','ui','ux','ml','devops','go','rust','postgres','mobile','data'] AS s)
INSERT INTO user_skills(user_id, skill)
SELECT DISTINCT ('00000000-0000-0000-0000-' || lpad(to_hex(n), 12, '0'))::uuid, skills.s[1 + (n * k) % 12]
FROM generate_series(1, 10000) n, generate_series(1, 4) k, skills
WHERE k <= 2 + n % 3;

-- 2,000 teams: team i is led by user i and has 1 + (i % 5) members (members drawn from users > 2000)
INSERT INTO teams(id, hackathon_id, leader_id, name, description, looking_for, max_size, member_count, status, created_at)
SELECT ('00000000-0000-0000-0001-' || lpad(to_hex(i), 12, '0'))::uuid,
       '00000000-0000-0000-0000-0000000000a1',
       ('00000000-0000-0000-0000-' || lpad(to_hex(i), 12, '0'))::uuid,
       'Team ' || i, 'Seeded team number ' || i,
       ARRAY[(ARRAY['java','react','python','ui','ux','ml','devops','go'])[1 + i % 8]],
       5, 1 + i % 5, CASE WHEN 1 + i % 5 >= 2 THEN 'READY' ELSE 'FORMING' END,
       now() - (i || ' seconds')::interval
FROM generate_series(1, 2000) i;

INSERT INTO team_members(team_id, user_id, hackathon_id, joined_at)
SELECT ('00000000-0000-0000-0001-' || lpad(to_hex(i), 12, '0'))::uuid,
       ('00000000-0000-0000-0000-' || lpad(to_hex(i), 12, '0'))::uuid,
       '00000000-0000-0000-0000-0000000000a1', now() - (i || ' seconds')::interval
FROM generate_series(1, 2000) i;

INSERT INTO team_members(team_id, user_id, hackathon_id, joined_at)
SELECT ('00000000-0000-0000-0001-' || lpad(to_hex(i), 12, '0'))::uuid,
       ('00000000-0000-0000-0000-' || lpad(to_hex(2000 + (i - 1) * 4 + k), 12, '0'))::uuid,
       '00000000-0000-0000-0000-0000000000a1', now() - (i || ' seconds')::interval + (k || ' seconds')::interval
FROM generate_series(1, 2000) i, generate_series(1, 4) k
WHERE k <= i % 5;
COMMIT;
ANALYZE;
SELECT (SELECT count(*) FROM users WHERE email LIKE '%@seed.test') AS users,
       (SELECT count(*) FROM teams WHERE hackathon_id = '00000000-0000-0000-0000-0000000000a1') AS teams,
       (SELECT count(*) FROM team_members WHERE hackathon_id = '00000000-0000-0000-0000-0000000000a1') AS memberships;
