-- Rotation chain FK made deferrable (data-model.md §4, FR-006).
--
-- The prescribed atomic rotation runs UPDATE-before-INSERT inside ONE
-- transaction:
--   UPDATE refresh_tokens SET status='rotated', replaced_by=:newId ...
--   rowcount=1 → INSERT the new active generation
-- so at UPDATE time the replacement row does not exist yet. V4 created the
-- self-FK NON-deferrable, which made every rotation fail immediately with
-- refresh_tokens_replaced_by_fkey (surfaced by the T026 IT once the /auth
-- endpoints went live in T033). DEFERRABLE INITIALLY DEFERRED keeps the
-- invariant (no dangling replaced_by past COMMIT) while allowing the
-- documented statement order; existing deployments stay consistent since
-- the constraint is re-created, not edited in place.

ALTER TABLE refresh_tokens DROP CONSTRAINT refresh_tokens_replaced_by_fkey;

ALTER TABLE refresh_tokens
    ADD CONSTRAINT refresh_tokens_replaced_by_fkey
    FOREIGN KEY (replaced_by) REFERENCES refresh_tokens (id) ON DELETE SET NULL
    DEFERRABLE INITIALLY DEFERRED;
