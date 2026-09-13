package webchat.backend.auth.domain.model

import java.time.Instant
import java.util.UUID

/**
 * Account identity (data-model.md §1; FR-001, FR-003).
 *
 * Lifecycle: pending_email_confirmation → awaiting_password → active, enforced via
 * [UserStatus.canTransitionTo]. `active` implies both a confirmed email and a set
 * password (DB constraint ck_users_active_implies_credentials).
 */
data class User(
    val id: UUID,
    val username: String,
    val email: String,
    val passwordHash: String?,
    val status: UserStatus,
    val emailConfirmedAt: Instant?,
    val passwordSetAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    val registrationIncomplete: Boolean
        get() = status != UserStatus.ACTIVE

    fun confirmEmail(at: Instant): User {
        require(status.canTransitionTo(UserStatus.AWAITING_PASSWORD)) {
            "email confirmation is only allowed from ${UserStatus.PENDING_EMAIL_CONFIRMATION}"
        }
        return copy(
            status = UserStatus.AWAITING_PASSWORD,
            emailConfirmedAt = at,
            updatedAt = at,
        )
    }

    fun setPassword(
        hash: String,
        at: Instant,
    ): User {
        require(status.canTransitionTo(UserStatus.ACTIVE)) {
            "password setup is only allowed from ${UserStatus.AWAITING_PASSWORD}"
        }
        return copy(
            status = UserStatus.ACTIVE,
            passwordHash = hash,
            passwordSetAt = at,
            updatedAt = at,
        )
    }

    companion object {
        fun register(
            id: UUID,
            username: String,
            email: String,
            at: Instant,
        ): User =
            User(
                id = id,
                username = username,
                email = email,
                passwordHash = null,
                status = UserStatus.PENDING_EMAIL_CONFIRMATION,
                emailConfirmedAt = null,
                passwordSetAt = null,
                createdAt = at,
                updatedAt = at,
            )
    }
}
