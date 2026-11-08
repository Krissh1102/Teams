-- V1: schema. PostgreSQL is the source of truth; the constraints below are the last line of defence.

CREATE TABLE users (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email          VARCHAR(254) NOT NULL UNIQUE,
    password_hash  VARCHAR(100) NOT NULL,
    display_name   VARCHAR(80)  NOT NULL,
    utc_offset_min INT NOT NULL DEFAULT 0 CHECK (utc_offset_min BETWEEN -720 AND 840),
    interests      TEXT[] NOT NULL DEFAULT '{}',
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE user_skills (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    skill   VARCHAR(40) NOT NULL,
    PRIMARY KEY (user_id, skill)
);

CREATE TABLE hackathons (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name          VARCHAR(120) NOT NULL,
    min_team_size INT NOT NULL CHECK (min_team_size >= 1),
    max_team_size INT NOT NULL,
    CHECK (max_team_size >= min_team_size)
);

CREATE TABLE teams (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    hackathon_id UUID NOT NULL REFERENCES hackathons(id),
    leader_id    UUID NOT NULL REFERENCES users(id),
    name         VARCHAR(80) NOT NULL,
    description  VARCHAR(500),
    looking_for  TEXT[] NOT NULL DEFAULT '{}',
    max_size     INT NOT NULL CHECK (max_size >= 1),
    member_count INT NOT NULL DEFAULT 0,
    status       VARCHAR(10) NOT NULL DEFAULT 'FORMING' CHECK (status IN ('FORMING','READY')),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_member_count CHECK (member_count >= 0 AND member_count <= max_size),
    CONSTRAINT uq_team_hackathon UNIQUE (id, hackathon_id)
);
CREATE INDEX idx_teams_hackathon_status ON teams(hackathon_id, status);
-- serves the live board: only teams with an open seat, newest first
CREATE INDEX idx_teams_board ON teams(hackathon_id, created_at DESC, id) WHERE member_count < max_size;

CREATE TABLE team_members (
    team_id      UUID NOT NULL,
    user_id      UUID NOT NULL REFERENCES users(id),
    hackathon_id UUID NOT NULL,
    joined_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (team_id, user_id),
    FOREIGN KEY (team_id, hackathon_id) REFERENCES teams(id, hackathon_id) ON DELETE CASCADE,
    -- FINAL safety net: one team per person per hackathon
    CONSTRAINT uq_user_hackathon UNIQUE (user_id, hackathon_id)
);
CREATE INDEX idx_team_members_team ON team_members(team_id, joined_at);

CREATE TABLE team_invites (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    team_id    UUID NOT NULL REFERENCES teams(id) ON DELETE CASCADE,
    code_hash  CHAR(64) NOT NULL UNIQUE,   -- SHA-256 hex of a 128-bit random code; raw code is never stored
    expires_at TIMESTAMPTZ NOT NULL,
    revoked    BOOLEAN NOT NULL DEFAULT FALSE,
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_team_invites_team ON team_invites(team_id);

CREATE TABLE join_requests (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    team_id    UUID NOT NULL REFERENCES teams(id) ON DELETE CASCADE,
    user_id    UUID NOT NULL REFERENCES users(id),
    status     VARCHAR(10) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','ACCEPTED','DECLINED')),
    message    VARCHAR(300),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_at TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_pending_request ON join_requests(team_id, user_id) WHERE status = 'PENDING';
CREATE INDEX idx_join_requests_team_status ON join_requests(team_id, status);

CREATE TABLE idempotency_keys (
    user_id       UUID NOT NULL,
    idem_key      VARCHAR(100) NOT NULL,
    operation     VARCHAR(80) NOT NULL,
    request_hash  CHAR(64) NOT NULL,
    response_code INT,
    response_body JSONB,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, idem_key)
);

-- Deliberately no FK: audit history must outlive a deleted team.
CREATE TABLE team_audit_logs (
    id         BIGSERIAL PRIMARY KEY,
    team_id    UUID NOT NULL,
    actor_id   UUID,
    action     VARCHAR(40) NOT NULL,
    target_id  UUID,
    details    JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_team_created ON team_audit_logs(team_id, created_at);

CREATE FUNCTION audit_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'team_audit_logs is append-only';
END; $$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_immutable
    BEFORE UPDATE OR DELETE ON team_audit_logs
    FOR EACH ROW EXECUTE FUNCTION audit_immutable();
