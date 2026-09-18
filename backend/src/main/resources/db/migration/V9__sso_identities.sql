-- SSO external identities + login-method invariants (data-model 003 §1, §3, §6).
--
-- external_identities: one external (provider_id, subject) pair belongs to
-- exactly one user (FR-006). No FK on provider_id on purpose — bindings survive
-- a provider being disabled or removed from configuration (US4-3).
--
-- sessions gains auth_method ('password' | 'sso'; NULL = legacy rows of 002,
-- treated as password) and identity_id ON DELETE SET NULL: unlinking an
-- identity never revokes its sessions (data-model 003 §3).
--
-- ck_users_active_implies_credentials (V1, equality) is replaced by the weaker
-- implication ck_users_active_implies_email: active => confirmed email. The
-- equality would reject 003 JIT accounts (active, password_hash NULL) and is
-- stronger than needed for 002 (awaiting_password has confirmed email while
-- status <> 'active'); all V1-compatible rows keep passing.

CREATE TABLE external_identities (
    id                      uuid         PRIMARY KEY,
    user_id                 uuid         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    provider_id             varchar(64)  NOT NULL,
    subject                 varchar(255) NOT NULL,
    provider_email          varchar(254) NULL,
    provider_email_verified boolean      NOT NULL DEFAULT false,
    linked_at               timestamptz  NOT NULL,
    CONSTRAINT ux_external_identities_provider_subject UNIQUE (provider_id, subject)
);

-- List of bindings per user: GET /users/me/identities (data-model 003 §1)
CREATE INDEX ix_external_identities_user ON external_identities (user_id);

ALTER TABLE sessions
    ADD COLUMN auth_method varchar(16),
    ADD COLUMN identity_id uuid REFERENCES external_identities (id) ON DELETE SET NULL;

ALTER TABLE users DROP CONSTRAINT ck_users_active_implies_credentials;

ALTER TABLE users ADD CONSTRAINT ck_users_active_implies_email
    CHECK (status <> 'active' OR email_confirmed_at IS NOT NULL);

-- Last-login-method guard (US3-4): an account with password_hash IS NULL must
-- keep at least one external binding. The BEFORE DELETE trigger closes the
-- concurrent-unlink race that app-level checks cannot (data-model 003 §6, §8);
-- the application maps the raised exception to 409 last_login_method (T034).
CREATE FUNCTION keep_at_least_one_login_method() RETURNS trigger AS $$
BEGIN
    -- Cascading from a deleted users row: the account itself is gone, the
    -- invariant only guards surviving accounts.
    IF NOT EXISTS (SELECT 1 FROM users u WHERE u.id = OLD.user_id) THEN
        RETURN OLD;
    END IF;
    IF (SELECT u.password_hash FROM users u WHERE u.id = OLD.user_id) IS NULL
       AND (SELECT count(*) FROM external_identities e
            WHERE e.user_id = OLD.user_id AND e.id <> OLD.id) = 0 THEN
        RAISE EXCEPTION 'last login method cannot be removed';
    END IF;
    RETURN OLD;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER trg_external_identities_keep_login_method
    BEFORE DELETE ON external_identities
    FOR EACH ROW EXECUTE FUNCTION keep_at_least_one_login_method();
