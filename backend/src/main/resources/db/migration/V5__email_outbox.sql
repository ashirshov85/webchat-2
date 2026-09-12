-- Entity 5: EmailOutbox (data-model.md §5; FR-008, SC-007)
-- Durable delivery queue through a replaceable gateway; inserted in the same TX
-- as user/OneTimeToken. Poller: SELECT ... FOR UPDATE SKIP LOCKED every 5 s.

CREATE TYPE email_type AS ENUM (
    'email_verification',
    'email_verification_repeat',
    'password_setup',
    'password_reset'
);

CREATE TYPE email_outbox_status AS ENUM ('pending', 'sent', 'failed_permanent');

CREATE TABLE email_outbox (
    id              uuid                PRIMARY KEY,
    user_id         uuid                NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    recipient_email varchar(254)        NOT NULL,
    email_type      email_type          NOT NULL,
    payload         jsonb               NOT NULL,
    status          email_outbox_status NOT NULL DEFAULT 'pending',
    attempts        int                 NOT NULL DEFAULT 0,
    next_attempt_at timestamptz         NOT NULL DEFAULT now(),
    last_error      text                NULL,
    sent_at         timestamptz         NULL,
    created_at      timestamptz         NOT NULL DEFAULT now(),
    CONSTRAINT ck_email_outbox_attempts_range CHECK (attempts BETWEEN 0 AND 10)
);

-- Poller scan: pending rows with next_attempt_at <= now()
CREATE INDEX idx_email_outbox_polling ON email_outbox (next_attempt_at) WHERE status = 'pending';

-- Resend cooldown: MAX(created_at) of the same step family per user,
-- regardless of status (data-model.md §5)
CREATE INDEX idx_email_outbox_cooldown ON email_outbox (user_id, email_type, created_at);
