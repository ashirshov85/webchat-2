package webchat.backend.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.ConfigurationPropertiesBindException
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.Duration

class AuthTokenPropertiesTest {
    private val contextRunner =
        ApplicationContextRunner()
            .withUserConfiguration(AuthTokenPropertiesConfiguration::class.java)

    @Test
    fun bindsTokenTtlsFromConfiguration() {
        contextRunner
            .withPropertyValues(
                "auth.token.access-ttl=5m",
                "auth.token.refresh-ttl=3d",
                "auth.token.email-verification-ttl=24h",
                "auth.token.password-setup-ttl=1h",
                "auth.token.password-reset-ttl=1h",
            ).run { context ->
                val properties = context.getBean(AuthTokenProperties::class.java)

                assertThat(properties.accessTtl).isEqualTo(Duration.ofMinutes(5))
                assertThat(properties.refreshTtl).isEqualTo(Duration.ofDays(3))
                assertThat(properties.emailVerificationTtl).isEqualTo(Duration.ofHours(24))
                assertThat(properties.passwordSetupTtl).isEqualTo(Duration.ofHours(1))
                assertThat(properties.passwordResetTtl).isEqualTo(Duration.ofHours(1))
            }
    }

    @Test
    fun missingKeyFailsStartup() {
        contextRunner.run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).isInstanceOf(ConfigurationPropertiesBindException::class.java)
        }
    }

    @EnableConfigurationProperties(AuthTokenProperties::class)
    private class AuthTokenPropertiesConfiguration
}
