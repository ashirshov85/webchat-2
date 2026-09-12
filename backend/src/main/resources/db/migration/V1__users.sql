-- Entity 1: User (data-model.md §1; FR-001, FR-003)
-- Case-insensitive uniqueness of username/email via unique expression indexes
-- on lower(...) — keeps the original case for display (research.md §1, §7).

CREATE TYPE user_status AS ENUM ('pending_email_confirmation', 'awaiting_password', 'active');

CREATE TABLE users (
    id                 uuid         PRIMARY KEY,
    username           varchar(32)  NOT NULL,
    email              varchar(254) NOT NULL,
    password_hash      varchar(255) NULL,
    status             user_status  NOT NULL,
    email_confirmed_at timestamptz  NULL,
    password_set_at    timestamptz  NULL,
    created_at         timestamptz  NOT NULL DEFAULT now(),
    updated_at         timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_users_username_format
        CHECK (username ~ '^[a-zA-Z0-9]([a-zA-Z0-9_.-]{1,30}[a-zA-Z0-9])$'),
    CONSTRAINT ck_users_active_implies_credentials
        CHECK ((status = 'active') = (email_confirmed_at IS NOT NULL AND password_hash IS NOT NULL))
);

CREATE UNIQUE INDEX ux_users_username_lower ON users (lower(username));
CREATE UNIQUE INDEX ux_users_email_lower    ON users (lower(email));
