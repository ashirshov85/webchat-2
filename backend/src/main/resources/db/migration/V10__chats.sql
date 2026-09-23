-- Entities 1-3: Chat / ChatParticipant / Message (data-model 004 §1-§3;
-- FR-001..FR-009, FR-018, FR-021).
--
-- chats: exactly one dialog per user pair (FR-001) — canonical pair order
-- (least/greatest) with a UNIQUE constraint; `last_seq` caches MAX(messages.seq)
-- maintained in the message INSERT transaction (list sorting FR-014, deletion
-- watermark FR-021, visibility) without a full MAX scan.
--
-- chat_participants: per-user dialog state — read watermark (monotone,
-- GREATEST-update), deletion watermark (message visible ⟺ seq >
-- deleted_up_to_seq, FR-021), `hidden` (list exclusion, reset by ensure or a
-- new incoming message).
--
-- messages: client-generated UUID id (dedup key + public identifier, FR-004);
-- server-side `seq` GENERATED ALWAYS AS IDENTITY — monotone and unique within
-- a chat only (UNIQUE (chat_id, seq)); no global UNIQUE (seq) by design.

CREATE TABLE chats (
    id           uuid        PRIMARY KEY,
    user_low_id  uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    user_high_id uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at   timestamptz NOT NULL DEFAULT now(),
    last_seq     bigint      NOT NULL DEFAULT 0,
    CONSTRAINT ux_chats_user_pair UNIQUE (user_low_id, user_high_id),
    CONSTRAINT ck_chats_low_lt_high CHECK (user_low_id < user_high_id)
);

CREATE TABLE chat_participants (
    chat_id           uuid        NOT NULL REFERENCES chats (id) ON DELETE CASCADE,
    user_id           uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    last_read_seq     bigint      NOT NULL DEFAULT 0,
    deleted_up_to_seq bigint      NOT NULL DEFAULT 0,
    hidden            boolean     NOT NULL DEFAULT false,
    created_at        timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (chat_id, user_id),
    CONSTRAINT ck_chat_participants_last_read_nonneg CHECK (last_read_seq >= 0),
    CONSTRAINT ck_chat_participants_deleted_up_to_nonneg CHECK (deleted_up_to_seq >= 0)
);

CREATE TABLE messages (
    id         uuid          PRIMARY KEY,
    chat_id    uuid          NOT NULL REFERENCES chats (id) ON DELETE CASCADE,
    sender_id  uuid          NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    text       varchar(4096) NOT NULL,
    seq        bigint        NOT NULL GENERATED ALWAYS AS IDENTITY,
    created_at timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT ux_messages_chat_seq UNIQUE (chat_id, seq),
    CONSTRAINT ck_messages_text_length CHECK (length(text) > 0 AND length(text) <= 4096)
);

-- History / pagination / list aggregates (data-model 004 §3): page reads are
-- ORDER BY seq DESC with the exclusive `before=<seq>` cursor.
CREATE INDEX ix_messages_chat_seq_desc ON messages (chat_id, seq DESC);
