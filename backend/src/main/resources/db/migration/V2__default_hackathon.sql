INSERT INTO hackathons (id, name, min_team_size, max_team_size)
VALUES ('00000000-0000-0000-0000-000000000001', 'Demo Hackathon 2026', 2, 5)
ON CONFLICT DO NOTHING;
