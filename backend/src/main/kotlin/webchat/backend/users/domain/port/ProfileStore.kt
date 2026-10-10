package webchat.backend.users.domain.port

import webchat.backend.users.domain.model.DisplayName
import webchat.backend.users.domain.model.Profile
import java.util.UUID

/**
 * Persistence port of the profile slice (data-model 008a §1.1; DIP: the
 * JDBC adapter lives outside the domain in
 * `webchat.backend.users.repository.JdbcProfileStore`, T011).
 *
 * `users` is an entity of 002: the auth aggregate
 * ([webchat.backend.auth.domain.port.UserRepository]) is NOT touched — a
 * profile write updates ONLY `display_name` (and the row's `updated_at`),
 * never password/status material.
 */
interface ProfileStore {
    /**
     * №39 set/clear leg: stores [displayName] — already normalized by
     * [DisplayName.normalize], i.e. the server-side trim happens BEFORE
     * this call (data-model 008a §1.1: «значение пишется после
     * серверного trim»); `null` resets the name to «not set».
     * Last-write-wins idempotent: a repeat of the same value is a plain
     * overwrite and by contract there is NO name-change realtime event
     * (refetch semantics, api-contract.md §1 №39). Returns the updated
     * row as [Profile] — the `200 PublicUser` body of №39; the write and
     * the read are ONE statement, so the answer cannot race a concurrent
     * write. Validation (`400 invalid_display_name`) and the flood
     * bucket (`rl:user:profile:{userId}`, 30/min) belong to the API
     * layer (T012); the caller is always an authenticated existing user
     * (`PUT /users/me/profile`), so the row is expected to exist.
     */
    fun updateDisplayName(
        userId: UUID,
        displayName: DisplayName?,
    ): Profile

    /**
     * The №10 read leg (T012): the public projection of one `users` row
     * WITH its optional [Profile.displayName] — the shared `PublicUser`
     * surface served by `GET /users/me` after 008a. The 002 aggregate is
     * NOT touched: this is a point read of the very columns №39 writes,
     * so a fresh №10 answer always carries the stored name. `null` when
     * the row does not exist — the same existence gate the auth
     * `UserRepository.findById` served before (the API layer renders its
     * uniform boundary problem).
     */
    fun findByUserId(userId: UUID): Profile?
}
