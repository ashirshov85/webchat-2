package webchat.backend.auth.domain.model

enum class UserStatus {
    PENDING_EMAIL_CONFIRMATION,
    AWAITING_PASSWORD,
    ACTIVE,
    ;

    fun canTransitionTo(next: UserStatus): Boolean =
        when (this) {
            PENDING_EMAIL_CONFIRMATION -> next == AWAITING_PASSWORD
            AWAITING_PASSWORD -> next == ACTIVE
            ACTIVE -> false
        }
}
