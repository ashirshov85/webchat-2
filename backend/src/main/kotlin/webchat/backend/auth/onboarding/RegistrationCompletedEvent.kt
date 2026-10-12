package webchat.backend.auth.onboarding

import java.util.UUID

data class RegistrationCompletedEvent(
    val userId: UUID,
)
