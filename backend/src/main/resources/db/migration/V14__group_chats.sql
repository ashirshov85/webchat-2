-- Group chats (data-model 006 §Сущности 1/2/4, «Миграция»; FR-001..FR-003,
-- FR-006, FR-008, FR-017).
--
-- chats: a group is a `kind='group'` row — common metadata, shared history,
-- exactly one owner (invariant enforced on chat_participants below). Direct
-- dialogs keep `kind='direct'` with the canonical user pair; the pair becomes
-- nullable (group rows carry NULLs, shape-CHECK) and the existing UNIQUE
-- (user_low_id, user_high_id) still holds for groups via PG NULLS DISTINCT.
-- title/description are group-only (CHECK; title is normalized by the service
-- with btrim — the CHECK re-checks the trimmed length, FR-001).
--
-- chat_participants: membership = active row. `role` (NULL ⟺ direct chat,
-- a group member always has one) + `state` ('active'|'removed'; direct rows
-- stay 'active' forever). Watermarks of 004/005 are reused as-is: first add
-- initializes last_read_seq/delivered_up_to_seq from chats.last_seq (FR-013),
-- re-adding a removed member keeps them (FR-002).
--
-- Partial indexes: ux_chat_participants_owner — storage-level "exactly one
-- owner" invariant (FR-003; ownership transfer is demote THEN promote in one
-- tx, so the index never breaks mid-way); ix_chat_participants_chat_active —
-- active roster / fanout / MIN-read-watermark / limit counter in a single
-- index-only scan (≤200 rows).
--
-- group_admin_log: append-only audit of administrative operations (FR-017),
-- NO FK on group_id — entries survive a group hard-delete (FR-006). Message
-- content is never logged; group events do not enter chat history (FR-015).
--
-- Additive; existing rows keep their behavior (defaults: all chats direct,
-- all participants active, role NULL) — no backfill needed. No down-path
-- (Flyway, convention 002).

ALTER TABLE chats
    ADD COLUMN kind varchar(16) NOT NULL DEFAULT 'direct',
    ADD COLUMN title varchar(64),
    ADD COLUMN description varchar(256),
    ALTER COLUMN user_low_id DROP NOT NULL,
    ALTER COLUMN user_high_id DROP NOT NULL;
ALTER TABLE chats ADD CONSTRAINT ck_chats_kind CHECK (kind IN ('direct','group'));
ALTER TABLE chats ADD CONSTRAINT ck_chats_shape CHECK (
    (kind='direct' AND user_low_id IS NOT NULL AND user_high_id IS NOT NULL)
    OR (kind='group' AND user_low_id IS NULL AND user_high_id IS NULL));
ALTER TABLE chats ADD CONSTRAINT ck_chats_title CHECK (
    kind='direct' OR (title IS NOT NULL AND length(btrim(title)) BETWEEN 1 AND 64));
ALTER TABLE chats ADD CONSTRAINT ck_chats_description CHECK (
    kind='direct' OR description IS NULL OR length(description) <= 256);

ALTER TABLE chat_participants
    ADD COLUMN role varchar(16),
    ADD COLUMN state varchar(16) NOT NULL DEFAULT 'active';
ALTER TABLE chat_participants ADD CONSTRAINT ck_chat_participants_role
    CHECK (role IN ('owner','admin','member') OR role IS NULL);
ALTER TABLE chat_participants ADD CONSTRAINT ck_chat_participants_state
    CHECK (state IN ('active','removed'));

CREATE UNIQUE INDEX ux_chat_participants_owner
    ON chat_participants (chat_id) WHERE role = 'owner';
CREATE INDEX ix_chat_participants_chat_active
    ON chat_participants (chat_id) INCLUDE (user_id, role, last_read_seq)
    WHERE state = 'active';

CREATE TABLE group_admin_log (
    id             uuid PRIMARY KEY,
    group_id       uuid NOT NULL,             -- no FK: survives group hard-delete
    actor_id       uuid NOT NULL REFERENCES users (id),
    target_user_id uuid REFERENCES users (id),
    action         varchar(32) NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_group_admin_log_action CHECK (action IN
        ('group_created','title_changed','description_changed','member_added',
         'member_removed','member_left','admin_granted','admin_revoked',
         'ownership_transferred','group_deleted'))
);
CREATE INDEX ix_group_admin_log_group ON group_admin_log (group_id, created_at);
