-- SSO auth event types (data-model 003 §6; research §3, FR-011).
--
-- ALTER TYPE ... ADD VALUE only, in a dedicated migration BEFORE any usage:
-- PostgreSQL (>= 12) allows ADD VALUE inside a transaction, but the new enum
-- labels cannot be used in the same transaction that created them. Rows with
-- these event types are first written by the SSO flow (IdentityResolution /
-- IdentityLink services) — nothing in V8 or earlier references them.
-- one_time_token_purpose is intentionally NOT extended: the SSO handshake
-- lives in Redis, not in one_time_tokens (research §2).
--
-- Values (data-model 003 §6, order preserved):
--   sso_login_success     — US1/US2 login completed (resolution in details)
--   sso_login_failed      — first-login refusals (unverified/conflict/incomplete)
--   sso_identity_linked   — US3 manual linking succeeded
--   sso_identity_unlinked — US3 unlinking succeeded
--   sso_account_created   — US2 JIT provisioning
--   sso_flow_error        — rejected flows (replay, bad ID-token, provider failure)

ALTER TYPE auth_event_type ADD VALUE 'sso_login_success';
ALTER TYPE auth_event_type ADD VALUE 'sso_login_failed';
ALTER TYPE auth_event_type ADD VALUE 'sso_identity_linked';
ALTER TYPE auth_event_type ADD VALUE 'sso_identity_unlinked';
ALTER TYPE auth_event_type ADD VALUE 'sso_account_created';
ALTER TYPE auth_event_type ADD VALUE 'sso_flow_error';
