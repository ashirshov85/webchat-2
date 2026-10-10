-- 008a-social-signals: durable-модель (data-model 008a §1; FR-001, FR-003,
-- FR-012). Строго аддитивные колонки к существующим таблицам — существующие
-- строки получают «не задано» / «включено» без backfill.
--
-- users.display_name: профильное имя (FR-001); NULL = «не задано» → цепочка
-- отображения падает на username. Значение пишется только после серверного
-- trim — CHECK перепроверяет длину обрезанной строки (1..64, US1 AC5).
--
-- user_contacts.alias: персональное имя контакта у владельца строки PK
-- (owner_id, contact_user_id) — per-user-per-contact по определению (FR-003);
-- NULL = нет alias, валидация как у display_name; удаляется вместе с
-- контактом (№22), переживает удаление чата; читается только владельцем.
--
-- chat_participants.sound_enabled: per-chat переключатель звука (FR-012),
-- умолчание TRUE — паритет 008 «всегда включено»; переживает скрытие/удаление
-- чата (строка участника сохраняется), новое участие стартует с TRUE.
--
-- No down-path (Flyway, convention 002).

ALTER TABLE users
    ADD COLUMN display_name varchar(64);
ALTER TABLE users ADD CONSTRAINT ck_users_display_name CHECK (
    display_name IS NULL OR (char_length(btrim(display_name)) BETWEEN 1 AND 64));

ALTER TABLE user_contacts
    ADD COLUMN alias varchar(64);
ALTER TABLE user_contacts ADD CONSTRAINT ck_user_contacts_alias CHECK (
    alias IS NULL OR (char_length(btrim(alias)) BETWEEN 1 AND 64));

ALTER TABLE chat_participants
    ADD COLUMN sound_enabled boolean NOT NULL DEFAULT TRUE;
