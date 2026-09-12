-- Entity 3: Session (data-model.md §3; FR-006, FR-010, FR-011)
-- Session = chain of refresh generations; id is the `sid` claim of access tokens.
-- Parallel sessions per user are allowed (edge spec).

CREATE TYPE session_status AS ENUM ('active', 'revoked', 'compromised');

CREATE TYPE session_revoked_reason AS ENUM ('logout', 'password_change', 'refresh_reuse_detected');

CREATE TABLE sessions (
    id                uuid                  PRIMARY KEY,
    user_id           uuid                  NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    status            session_status        NOT NULL,
    created_at        timestamptz           NOT NULL DEFAULT now(),
    last_refreshed_at timestamptz           NOT NULL DEFAULT now(),
    revoked_at        timestamptz           NULL,
    revoked_reason    session_revoked_reason NULL
);

-- revokeAllForUser: UPDATE ... WHERE user_id = ? AND status = 'active' (data-model.md §4)
CREATE INDEX idx_sessions_user_active ON sessions (user_id) WHERE status = 'active';
