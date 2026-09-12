package webchat.backend.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "auth.token")
data class AuthTokenProperties(
    val accessTtl: Duration,
    val refreshTtl: Duration,
    val emailVerificationTtl: Duration,
    val passwordSetupTtl: Duration,
    val passwordResetTtl: Duration,
)
