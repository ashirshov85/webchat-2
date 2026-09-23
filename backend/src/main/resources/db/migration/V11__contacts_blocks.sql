-- Entities 4-5: Contact / UserBlock (data-model 004 §4-§5; FR-016..FR-021).
--
-- user_contacts: one-sided contact list entries (FR-016). Composite PK on the
-- (owner, contact_user) pair makes re-adding idempotent at the storage level
-- (edge spec); the CHECK is a second line of defense — the primary self-guard
-- is the service layer (422 self_forbidden).
--
-- user_blocks: one-sided block relation managed by the blocker (FR-020).
-- PK on the (blocker, blocked) pair; all block semantics are point lookups on
-- this pair in both directions. PUT/DELETE are idempotent (ON CONFLICT DO
-- NOTHING / unconditional DELETE → 204).
--
-- Contacts and blocks are fully independent of each other and of
-- chats/chat_participants (FR-017, FR-020): no FKs beyond users, tables of
-- 002 and V10 are not touched.

CREATE TABLE user_contacts (
    owner_id        uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    contact_user_id uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (owner_id, contact_user_id),
    CONSTRAINT ck_user_contacts_not_self CHECK (contact_user_id <> owner_id)
);

CREATE TABLE user_blocks (
    blocker_id uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    blocked_id uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (blocker_id, blocked_id),
    CONSTRAINT ck_user_blocks_not_self CHECK (blocker_id <> blocked_id)
);
