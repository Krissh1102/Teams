-- Run after any load test, race test or crash test. EVERY row returned is a bug; no rows = consistent.
SELECT 'counter_drift' AS violation, t.id::text AS detail
  FROM teams t WHERE t.member_count <> (SELECT count(*) FROM team_members m WHERE m.team_id = t.id)
UNION ALL SELECT 'over_capacity', id::text FROM teams WHERE member_count > max_size
UNION ALL SELECT 'duplicate_membership', user_id::text || '/' || hackathon_id::text
  FROM team_members GROUP BY user_id, hackathon_id HAVING count(*) > 1
UNION ALL SELECT 'leader_not_member', t.id::text
  FROM teams t WHERE NOT EXISTS (SELECT 1 FROM team_members m WHERE m.team_id = t.id AND m.user_id = t.leader_id)
UNION ALL SELECT 'empty_team_not_deleted', t.id::text
  FROM teams t WHERE NOT EXISTS (SELECT 1 FROM team_members m WHERE m.team_id = t.id)
UNION ALL SELECT 'wrong_status', t.id::text
  FROM teams t JOIN hackathons h ON h.id = t.hackathon_id
  WHERE t.status <> CASE WHEN t.member_count >= h.min_team_size THEN 'READY' ELSE 'FORMING' END;
