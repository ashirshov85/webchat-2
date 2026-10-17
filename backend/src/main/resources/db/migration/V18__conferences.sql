-- Video conferences (data-model 009 §Сущности 1-4; FR-001..FR-004, FR-008,
-- FR-009..FR-012, FR-014..FR-015).
--
-- conferences: a call bound to a chat (direct or group), LiveKit room c:{id}.
-- Media state (rooms, connections, tracks, connection mutes) lives in LiveKit
-- and is NOT duplicated here (research §4/§7): the DB keeps only what must
-- survive reconnect (roles, restrictions) and outlive the call (the fact of
-- it). Status moves only forward: created → active (first participant_joined
-- webhook, conditional UPDATE) →| created → ended (finalize: room_finished
-- webhook / №48 DeleteRoom cascade / max-duration / sweeper / chat deletion
-- event BEFORE CASCADE). started_at is set by the first participant_joined;
-- a conference ended while still 'created' (empty-timeout, room_lost) keeps
-- it NULL — hence the directional CHECKs below.
--
-- ux_conferences_chat_active: storage-level "one non-ended conference per
-- chat" (FR-001 edge "double press": a competing create loses, the service
-- returns the existing row — idempotency; concurrent INSERTs serialize on
-- the partial index, IT invariant 1). ix_conferences_status: sweeper scan +
-- active-conferences gauge (research §7/§13).
--
-- conference_participants: principal row — role + moderation restrictions
-- surviving reconnect (Q5/Q9/Q22). Member = one row per (conference_id,
-- user_id) (partial UNIQUE); guest = a row per connection (new join = new
-- row, Q7 — restrictions do not survive guest reconnect). identity is the
-- LiveKit connection-identity PREFIX u:{userId} / g:{connId} (connection
-- identities are u:{userId}:{connId} / g:{connId}, research §4); webhooks
-- arrive scoped by room c:{conferenceId}, so principal lookup by parsed
-- prefix is exact within the conference — UNIQUE (conference_id, identity);
-- a global UNIQUE would forbid the same user principal in two chats' calls.
-- Shape-CHECK: guest must carry a display_name, member must not (name comes
-- from users at token mint). All rows are deleted on finalize (Q18: the
-- roster of a finished conference is neither kept nor shown — privacy).
--
-- conference_guest_invites: minimal status row behind the stateless signed
-- link (JWT nonce, research §6) — revocation + "exactly one active link per
-- conference" (ux_guest_invites_active; new-link generation is
-- UPDATE … revoke + INSERT in one tx, Q17). Not posted to the chat. Rows are
-- deleted on finalize (Q18).
--
-- messages evolution (entity 4, system widgets): first non-user message
-- kind — the invite card. kind='user' keeps sender_id NOT NULL + no payload;
-- kind='system' has sender_id NULL + jsonb payload (object; payload.type
-- dispatch, size ≤ 4 KB is service-validated) + text as the placeholder/
-- widget caption. Existing rows default to kind='user', values untouched.
--
-- chats.e2ee: v1 boundary with 010 (E2EE chats reject conference create,
-- FR-001) — additive boolean, default false.
--
-- Additive; no backfill needed. No down-path (Flyway, convention 002).

CREATE TABLE conferences (
    id               uuid         PRIMARY KEY,
    chat_id          uuid         NOT NULL REFERENCES chats (id) ON DELETE CASCADE,
    created_by       uuid         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    status           varchar(16)  NOT NULL,
    max_participants integer      NOT NULL,
    created_at       timestamptz  NOT NULL DEFAULT now(),
    started_at       timestamptz,
    ended_at         timestamptz,
    end_reason       varchar(32),
    CONSTRAINT ck_conferences_status CHECK (status IN ('created','active','ended')),
    CONSTRAINT ck_conferences_max_participants CHECK (max_participants > 0),
    CONSTRAINT ck_conferences_end_reason CHECK (end_reason IN
        ('last_left','moderator','max_duration','room_lost','chat_deleted')),
    -- ended ⟺ ended_at/end_reason set (finalize idempotency anchor, invariant 5)
    CONSTRAINT ck_conferences_ended_at CHECK ((status = 'ended') = (ended_at IS NOT NULL)),
    CONSTRAINT ck_conferences_end_reason_set CHECK ((status = 'ended') = (end_reason IS NOT NULL)),
    -- started_at: first participant_joined; NULL while 'created', set once
    -- 'active'; 'ended' via created→ended (room_lost) legitimately keeps NULL
    CONSTRAINT ck_conferences_started_created CHECK (status <> 'created' OR started_at IS NULL),
    CONSTRAINT ck_conferences_started_active CHECK (status <> 'active' OR started_at IS NOT NULL)
);

CREATE UNIQUE INDEX ux_conferences_chat_active
    ON conferences (chat_id) WHERE status <> 'ended';
CREATE INDEX ix_conferences_status ON conferences (status);

CREATE TABLE conference_participants (
    id                uuid         PRIMARY KEY,
    conference_id     uuid         NOT NULL REFERENCES conferences (id) ON DELETE CASCADE,
    identity          varchar(80)  NOT NULL,
    user_id           uuid         REFERENCES users (id) ON DELETE CASCADE,
    display_name      varchar(64),
    role              varchar(16)  NOT NULL DEFAULT 'participant',
    mic_restricted    boolean      NOT NULL DEFAULT false,
    camera_restricted boolean      NOT NULL DEFAULT false,
    screen_restricted boolean      NOT NULL DEFAULT false,
    speech_restricted boolean      NOT NULL DEFAULT false,
    first_seen_at     timestamptz  NOT NULL DEFAULT now(),
    last_joined_at    timestamptz,
    last_left_at      timestamptz,
    CONSTRAINT ck_conference_participants_role CHECK (role IN ('moderator','participant')),
    -- guest ⟺ named; member takes the name from users at mint
    CONSTRAINT ck_conference_participants_shape
        CHECK ((user_id IS NULL) = (display_name IS NOT NULL))
);

CREATE UNIQUE INDEX ux_conference_participants_member
    ON conference_participants (conference_id, user_id) WHERE user_id IS NOT NULL;
CREATE UNIQUE INDEX ux_conference_participants_identity
    ON conference_participants (conference_id, identity);
CREATE INDEX ix_conference_participants_conference
    ON conference_participants (conference_id);

CREATE TABLE conference_guest_invites (
    id            uuid        PRIMARY KEY,
    conference_id uuid        NOT NULL REFERENCES conferences (id) ON DELETE CASCADE,
    created_by    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    status        varchar(16) NOT NULL,
    expires_at    timestamptz NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    revoked_at    timestamptz,
    CONSTRAINT ck_conference_guest_invites_status CHECK (status IN ('active','revoked')),
    CONSTRAINT ck_conference_guest_invites_expires CHECK (expires_at > created_at),
    CONSTRAINT ck_conference_guest_invites_revoked
        CHECK ((status = 'revoked') = (revoked_at IS NOT NULL))
);

CREATE UNIQUE INDEX ux_guest_invites_active
    ON conference_guest_invites (conference_id) WHERE status = 'active';

ALTER TABLE messages
    ADD COLUMN kind varchar(16) NOT NULL DEFAULT 'user',
    ADD COLUMN payload jsonb,
    ALTER COLUMN sender_id DROP NOT NULL;
ALTER TABLE messages ADD CONSTRAINT ck_messages_kind CHECK (kind IN ('user','system'));
ALTER TABLE messages ADD CONSTRAINT ck_messages_kind_shape CHECK (
    (kind = 'user' AND sender_id IS NOT NULL AND payload IS NULL)
    OR (kind = 'system' AND sender_id IS NULL AND payload IS NOT NULL));
ALTER TABLE messages ADD CONSTRAINT ck_messages_payload_object
    CHECK (jsonb_typeof(payload) = 'object');

ALTER TABLE chats ADD COLUMN e2ee boolean NOT NULL DEFAULT false;
