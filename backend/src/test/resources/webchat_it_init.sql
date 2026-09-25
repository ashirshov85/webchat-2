-- IT database bootstrap (AbstractIntegrationTest): the app pool must NOT
-- run as the Testcontainers bootstrap superuser (POSTGRES_USER) — PG 15+
-- forbids demoting it, and superusers bypass row-level security even
-- under FORCE, which would silently defeat the RLS-based per-chat fault
-- injection of SyncIT (T008, all-or-refusal). This dedicated non-superuser
-- login is what the Spring context (and its Flyway migrations, owning every
-- schema object) connects as.
CREATE ROLE webchat_app LOGIN PASSWORD 'webchat_app';
GRANT CREATE, USAGE ON SCHEMA public TO webchat_app;
