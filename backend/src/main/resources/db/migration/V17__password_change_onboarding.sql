-- 008b-auth-ux-polish: onboarding flag + password-change audit enums (data-model 008b §1.1–1.2).
--
-- (a) users.starter_contact_added — onboarding discriminator (FR-011): has the account
--     ever received the admin starter contact. Monotone FALSE→TRUE, never reset.
-- (b) Backfill-free mark: accounts existing at deploy time are set to TRUE ("processed")
--     WITHOUT adding any contacts (no backfill — clarify spec.md).
-- (c) ALTER TYPE ... ADD VALUE only, in a dedicated migration BEFORE any usage:
--     PostgreSQL (>= 12) allows ADD VALUE inside a transaction, but the new enum
--     labels cannot be used in the same transaction that created them — nothing in
--     V17 or earlier references them; rows with these event types are first written
--     by PasswordChangeService (US2). Precedent V8__sso_enums.sql:19-24.

ALTER TABLE users ADD COLUMN starter_contact_added BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE users SET starter_contact_added = TRUE;

ALTER TYPE auth_event_type ADD VALUE 'password_change_completed';
ALTER TYPE auth_event_type ADD VALUE 'password_change_failed';
