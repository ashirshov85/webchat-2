package webchat.backend.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import webchat.backend.auth.domain.port.Clock
import java.time.Instant

/**
 * Production implementation of the domain [Clock] port (T010): domain services
 * depend on the port, tests can substitute a controlled instance.
 */
@Configuration
class ClockConfig {
    @Bean
    fun clock(): Clock = Clock { Instant.now() }
}
