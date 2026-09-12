package webchat.backend.auth.domain.model

enum class TokenPurpose {
    EMAIL_VERIFICATION,
    PASSWORD_SETUP,
    PASSWORD_RESET,
}
