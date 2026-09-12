-- Entity 2: OneTimeToken (data-model.md §2; FR-002, FR-010, FR-012)
-- Single-use links from emails; only the SHA-256 hash of the opaque value is stored.
-- Conditional consumption (used_at / expires_at guards) is app-level (FR-012).

CREATE TYPE one_time_token_purpose AS ENUM ('email_verification', 'password_setup', 'password_reset');

CREATE TABLE one_time_tokens (
    id         uuid                   PRIMARY KEY,
    user_id    uuid                   NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    purpose    one_time_token_purpose NOT NULL,
    token_hash char(64)               NOT NULL,
    expires_at timestamptz            NOT NULL,
    used_at    timestamptz            NULL,
    created_at timestamptz            NOT NULL DEFAULT now(),
    CONSTRAINT uq_one_time_tokens_token_hash UNIQUE (token_hash)
);

-- Annihilation of previous active tokens of the same user+purpose (data-model.md §2)
CREATE INDEX idx_one_time_tokens_user_purpose ON one_time_tokens (user_id, purpose);
