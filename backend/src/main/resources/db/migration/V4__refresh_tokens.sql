-- Entity 4: RefreshToken (data-model.md §4; FR-006)
-- Generation of a refresh chain; CAS rotation via conditional
-- UPDATE ... WHERE id = ? AND token_hash = ? AND status = 'active' AND expires_at > now().

CREATE TYPE refresh_token_status AS ENUM ('active', 'rotated', 'revoked');

CREATE TABLE refresh_tokens (
    id          uuid                 PRIMARY KEY,
    session_id  uuid                 NOT NULL REFERENCES sessions (id) ON DELETE CASCADE,
    token_hash  char(64)             NOT NULL,
    status      refresh_token_status NOT NULL,
    expires_at  timestamptz          NOT NULL,
    replaced_by uuid                 NULL REFERENCES refresh_tokens (id) ON DELETE SET NULL,
    created_at  timestamptz          NOT NULL DEFAULT now(),
    CONSTRAINT uq_refresh_tokens_token_hash UNIQUE (token_hash)
);

CREATE INDEX idx_refresh_tokens_session ON refresh_tokens (session_id);
