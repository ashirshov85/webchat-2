package webchat.backend.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.core.io.Resource

@ConfigurationProperties(prefix = "auth.password")
data class AuthPasswordProperties(
    val blocklistPath: Resource,
)
