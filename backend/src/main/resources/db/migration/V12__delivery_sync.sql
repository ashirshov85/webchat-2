-- Entity 1 evolution: ChatParticipant.delivered_up_to_seq (data-model 005
-- «Миграция»; FR-001, FR-003).
--
-- Delivery position: advanced ONLY by delivery-ack №25 (monotone
-- GREATEST-update, rowcount 0 = no effect); neither the sync №26 response nor
-- SSE frames write it. Backfill GREATEST(last_read_seq, deleted_up_to_seq)
-- keeps existing rows "read/deleted knowingly behind" — nothing already read
-- or deleted is re-delivered as new; unread-but-delivered history is picked
-- up by the first catch-up sync.
--
-- Index: ack-updates go by PK (chat_id, user_id); selecting the user's chats
-- with undelivered content in №26 needs a per-user row list (the PK leads
-- with chat_id, a full scan is wasteful) — ix_chat_participants_user
-- (tens of rows per user, top-N is re-ranked by chats.last_seq).
--
-- Existing tables of 002/004 are not modified; no down-path (Flyway,
-- convention 002).

ALTER TABLE chat_participants
    ADD COLUMN delivered_up_to_seq bigint NOT NULL DEFAULT 0;

ALTER TABLE chat_participants
    ADD CONSTRAINT ck_chat_participants_delivered_up_to_nonneg
        CHECK (delivered_up_to_seq >= 0);

UPDATE chat_participants
SET delivered_up_to_seq = GREATEST(last_read_seq, deleted_up_to_seq);

CREATE INDEX ix_chat_participants_user ON chat_participants (user_id);
