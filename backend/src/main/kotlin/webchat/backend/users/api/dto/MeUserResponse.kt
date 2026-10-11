package webchat.backend.users.api.dto

import com.fasterxml.jackson.annotation.JsonInclude
import java.time.Instant
import java.util.UUID

/**
 * Contract №10 success body of 0.10.0 (008b T014, research.md §4): the
 * inline `allOf(PublicUser, hasPassword)` composition — every field of
 * [PublicUserResponse] plus the additive REQUIRED [hasPassword] that
 * gates the profile password form (FR-004): `true` — a password
 * account, `false` — the password-less JIT account of SSO 003
 * (`users.password_hash IS NULL`; the form stays hidden and offers the
 * 002 email-recovery link instead).
 *
 * Served ONLY by `GET /users/me` — the token owner reads the marker of
 * its OWN account. The peer/search/member surfaces keep rendering the
 * bare [PublicUserResponse] projection: a `hasPassword` inside
 * `PublicUser` itself would disclose the JIT origin of an account to
 * OTHER users (research.md 008b §4, alternative (a) — rejected).
 */
data class MeUserResponse(
    val id: UUID,
    val username: String,
    val email: String,
    val status: String,
    val createdAt: Instant,
    val hasPassword: Boolean,
    @JsonInclude(JsonInclude.Include.NON_NULL) val displayName: String? = null,
) {
    companion object {
        /** Lifts the shared `PublicUser` projection into the №10 body of 0.10.0. */
        fun of(
            publicUser: PublicUserResponse,
            hasPassword: Boolean,
        ): MeUserResponse =
            with(publicUser) {
                MeUserResponse(
                    id = id,
                    username = username,
                    email = email,
                    status = status,
                    createdAt = createdAt,
                    hasPassword = hasPassword,
                    displayName = displayName,
                )
            }
    }
}
