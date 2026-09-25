-- T047: harden the last-login-method guard against the concurrent-unlink
-- race under READ COMMITTED. The V9 revision of the trigger counted the
-- surviving bindings WITHOUT locking the users row, so two parallel DELETE
-- transactions (passwordless JIT account, two bindings — SsoLinkingIT
-- "parallel unlink of two bindings") each saw the OTHER identity as still
-- present (an uncommitted DELETE stays invisible to the counter), and both
-- unlinks committed with 204, violating US3-4 (data-model 003 §6, §8).
-- Taking the users row lock first serializes the trigger executions: the
-- second DELETE re-reads the binding count only after the first commits,
-- sees zero remaining methods and raises — the app maps that to
-- 409 last_login_method, as before.
CREATE OR REPLACE FUNCTION keep_at_least_one_login_method() RETURNS trigger AS $$
BEGIN
    -- Cascading from a deleted users row: the account itself is gone, the
    -- invariant only guards surviving accounts.
    IF NOT EXISTS (SELECT 1 FROM users u WHERE u.id = OLD.user_id) THEN
        RETURN OLD;
    END IF;
    -- Serialize concurrent unlinks of one account BEFORE counting: without
    -- this lock the count below cannot see a concurrent uncommitted DELETE.
    PERFORM 1 FROM users u WHERE u.id = OLD.user_id FOR UPDATE;
    IF (SELECT u.password_hash FROM users u WHERE u.id = OLD.user_id) IS NULL
       AND (SELECT count(*) FROM external_identities e
            WHERE e.user_id = OLD.user_id AND e.id <> OLD.id) = 0 THEN
        RAISE EXCEPTION 'last login method cannot be removed';
    END IF;
    RETURN OLD;
END $$ LANGUAGE plpgsql;
