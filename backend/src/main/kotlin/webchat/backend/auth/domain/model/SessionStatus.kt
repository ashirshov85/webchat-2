package webchat.backend.auth.domain.model

enum class SessionStatus {
    ACTIVE,
    REVOKED,
    COMPROMISED,
    ;

    fun canTransitionTo(next: SessionStatus): Boolean =
        when (this) {
            ACTIVE -> next == REVOKED || next == COMPROMISED
            REVOKED, COMPROMISED -> false
        }
}

enum class RevokedReason {
    LOGOUT,
    PASSWORD_CHANGE,
    REFRESH_REUSE_DETECTED,
}
