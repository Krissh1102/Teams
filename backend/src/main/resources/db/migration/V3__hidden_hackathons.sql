-- Throwaway hackathons (Race Arena fixtures, chaos tests) must never appear in the real UI.
ALTER TABLE hackathons ADD COLUMN hidden BOOLEAN NOT NULL DEFAULT FALSE;
