-- Entity 6: AuthEvent (data-model.md §6; FR-013)
-- Security journal without secrets: no passwords, no open tokens, no raw PII.
-- ip_hash = SHA-256(IP + server-side pepper), never the raw IP.

CREATE TYPE auth_event_type AS ENUM (
    'register_requested',
    'email_confirmed',
    'password_set',
    'login_success',
    'login_failed',
    'login_throttled',
    'refresh_rotated',
    'refresh_reuse_detected',
    'logout',
    'password_reset_requested',
    'password_reset_completed',
    'session_revoked'
);

CREATE TABLE auth_events (
    id          uuid            PRIMARY KEY,
    user_id     uuid            NULL REFERENCES users (id) ON DELETE SET NULL,
    event_type  auth_event_type NOT NULL,
    occurred_at timestamptz     NOT NULL DEFAULT now(),
    ip_hash     varchar(64)     NOT NULL,
    user_agent  varchar(256)    NULL,
    details     jsonb           NOT NULL DEFAULT '{}'::jsonb,
    trace_id    varchar(64)     NULL
);

CREATE INDEX idx_auth_events_user_occurred  ON auth_events (user_id, occurred_at);
CREATE INDEX idx_auth_events_type_occurred  ON auth_events (event_type, occurred_at);
