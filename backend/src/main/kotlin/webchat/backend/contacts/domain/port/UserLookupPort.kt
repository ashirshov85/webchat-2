package webchat.backend.contacts.domain.port

import webchat.backend.contacts.domain.model.UserProfile
import java.util.UUID

/**
 * Read-only lookup port over the `users` (002) tables (DIP: the entity
 * belongs to feature 002 — here it is only read; the JDBC adapter lives
 * outside the domain in `webchat.backend.contacts.repository.JdbcUserLookup`,
 * T051). Backs №19 `GET /users/search` and the `404 user_not_found` /
 * `422 self_forbidden` target checks of №21/№23 (T052).
 */
interface UserLookupPort {
    /**
     * Direct read by id: the public profile of the user, or `null` when
     * no such user exists → `404 user_not_found` (№21 `POST /contacts`,
     * №23 `PUT /users/{userId}/block`). The caller is always a known user,
     * so the self-refusal (`422 self_forbidden`) never competes with this
     * `null` branch.
     */
    fun findById(userId: UUID): UserProfile?

    /**
     * №19 `GET /users/search?query=` — the EXACT match of a full email OR
     * a full login, case-insensitively (FR-016), following the @-rule:
     * `query` contains `@` → compare `lower(email)`, otherwise
     * `lower(username)` — a username cannot contain `@` (002 rule), so
     * the branches are disjoint. Result is 0..1: `null` is a CORRECT
     * answer (no match, edge — rendered as an empty list, not an error).
     * [query] arrives non-blank and at most 254 chars (the `400
     * query_missing` validation of №19 belongs to the API layer, T053);
     * flood protection (FR-016, 30/min) is applied BEFORE this call
     * (T053a).
     */
    fun searchExact(query: String): UserProfile?
}
