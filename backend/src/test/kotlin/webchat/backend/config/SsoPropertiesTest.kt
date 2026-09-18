package webchat.backend.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.ConfigurationPropertiesBindException
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.Duration

class SsoPropertiesTest {
    private val contextRunner =
        ApplicationContextRunner()
            .withUserConfiguration(SsoPropertiesConfiguration::class.java)
            .withPropertyValues(
                "sso.flow-ttl=10m",
                "sso.handshake-ttl=2m",
            )

    @Test
    fun bindsValuesFromApplicationYml() {
        ApplicationContextRunner()
            .withInitializer(ConfigDataApplicationContextInitializer())
            .withUserConfiguration(SsoPropertiesConfiguration::class.java)
            .run { context ->
                assertThat(context).hasNotFailed()
                val properties = context.getBean(SsoProperties::class.java)

                assertThat(properties.flowTtl).isEqualTo(Duration.ofMinutes(10))
                assertThat(properties.handshakeTtl).isEqualTo(Duration.ofMinutes(2))
                // T042: local-dev default — the backend answers on :8080 directly
                // (no ingress), matching the dex redirect-uri of quickstart «Предусловия»
                assertThat(properties.callbackUrl)
                    .isEqualTo("http://localhost:8080/api/v1/auth/sso/callback")
                // T042: the only predefined provider is the opt-in local dex IdP —
                // disabled without SSO_DEX_ENABLED, empty-secret default allowed by
                // the fail-fast validation (T007)
                assertThat(properties.providers.keys).containsExactly("dex")
                val dex = properties.providers.getValue("dex")
                assertThat(dex.enabled).isFalse()
                assertThat(dex.clientId).isEqualTo("webchat")
                assertThat(dex.clientSecret).isEmpty()
                assertThat(dex.trustedForEmailLinking).isTrue()
                assertThat(dex.authorizationUri).isEqualTo("http://localhost:5556/auth")
                assertThat(dex.tokenUri).isEqualTo("http://localhost:5556/token")
            }
    }

    @Test
    fun bindsCommonSettings() {
        contextRunner
            .withPropertyValues(
                "sso.callback-url=http://localhost:8080/api/v1/auth/sso/callback",
                "sso.flow-ttl=10m",
                "sso.handshake-ttl=2m",
            ).run { context ->
                val properties = context.getBean(SsoProperties::class.java)

                assertThat(properties.callbackUrl)
                    .isEqualTo("http://localhost:8080/api/v1/auth/sso/callback")
                assertThat(properties.flowTtl).isEqualTo(Duration.ofMinutes(10))
                assertThat(properties.handshakeTtl).isEqualTo(Duration.ofMinutes(2))
            }
    }

    @Test
    fun missingCallbackUrlFailsStartup() {
        contextRunner.run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure)
                .isInstanceOf(ConfigurationPropertiesBindException::class.java)
        }
    }

    @Test
    fun missingFlowTtlFailsStartup() {
        ApplicationContextRunner()
            .withUserConfiguration(SsoPropertiesConfiguration::class.java)
            .withPropertyValues(
                "sso.callback-url=http://localhost:8080/api/v1/auth/sso/callback",
                "sso.handshake-ttl=2m",
            ).run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure)
                    .isInstanceOf(ConfigurationPropertiesBindException::class.java)
            }
    }

    @Test
    fun bindsProvidersInDeclarationOrder() {
        contextRunner
            .withPropertyValues(
                "sso.callback-url=http://localhost:8080/api/v1/auth/sso/callback",
                "sso.providers.zulu.issuer-uri=https://zulu.example.com",
                "sso.providers.zulu.display-name=Zulu IdP",
                "sso.providers.zulu.client-id=zulu-client",
                "sso.providers.zulu.client-secret=zulu-secret",
                "sso.providers.alpha.display-name=Alpha IdP",
                "sso.providers.alpha.client-id=alpha-client",
                "sso.providers.alpha.client-secret=alpha-secret",
                "sso.providers.alpha.authorization-uri=https://alpha.example.com/authorize",
                "sso.providers.alpha.token-uri=https://alpha.example.com/token",
                "sso.providers.alpha.jwks-uri=https://alpha.example.com/jwks",
            ).run { context ->
                val properties = context.getBean(SsoProperties::class.java)

                assertThat(properties.providers.keys).containsExactly("zulu", "alpha")
            }
    }

    @Test
    fun appliesProviderDefaults() {
        contextRunner
            .withPropertyValues(
                "sso.callback-url=http://localhost:8080/api/v1/auth/sso/callback",
                "sso.providers.google.issuer-uri=https://accounts.google.com",
                "sso.providers.google.display-name=Google",
                "sso.providers.google.client-id=google-client",
                "sso.providers.google.client-secret=google-secret",
            ).run { context ->
                val provider = context.getBean(SsoProperties::class.java).providers.getValue("google")

                assertThat(provider.enabled).isTrue()
                assertThat(provider.trustedForEmailLinking).isFalse()
                assertThat(provider.scopes).containsExactly("openid", "email")
            }
    }

    @Test
    fun bindsExplicitProviderSettings() {
        contextRunner
            .withPropertyValues(
                "sso.callback-url=http://localhost:8080/api/v1/auth/sso/callback",
                "sso.providers.dex.display-name=Dex",
                "sso.providers.dex.enabled=true",
                "sso.providers.dex.trusted-for-email-linking=true",
                "sso.providers.dex.client-id=webchat",
                "sso.providers.dex.client-secret=dev-secret",
                "sso.providers.dex.authorization-uri=http://localhost:5556/auth",
                "sso.providers.dex.token-uri=http://localhost:5556/token",
                "sso.providers.dex.userinfo-uri=http://localhost:5556/userinfo",
                "sso.providers.dex.jwks-uri=http://localhost:5556/keys",
                "sso.providers.dex.scopes=openid,email,profile",
            ).run { context ->
                val provider = context.getBean(SsoProperties::class.java).providers.getValue("dex")

                assertThat(provider.trustedForEmailLinking).isTrue()
                assertThat(provider.scopes).containsExactly("openid", "email", "profile")
            }
    }

    @Test
    fun disabledProviderWithEmptySecretStarts() {
        contextRunner
            .withPropertyValues(
                "sso.callback-url=http://localhost:8080/api/v1/auth/sso/callback",
                "sso.providers.dex.display-name=Dex",
                "sso.providers.dex.enabled=false",
                "sso.providers.dex.client-id=webchat",
                "sso.providers.dex.client-secret=",
                "sso.providers.dex.issuer-uri=http://localhost:5556/dex",
            ).run { context ->
                assertThat(context).hasNotFailed()
                val provider = context.getBean(SsoProperties::class.java).providers.getValue("dex")

                assertThat(provider.enabled).isFalse()
                assertThat(provider.clientSecret).isEmpty()
            }
    }

    @Test
    fun enabledProviderWithBlankClientIdFailsStartup() {
        contextRunner
            .withPropertyValues(
                "sso.callback-url=http://localhost:8080/api/v1/auth/sso/callback",
                "sso.providers.google.display-name=Google",
                "sso.providers.google.issuer-uri=https://accounts.google.com",
                "sso.providers.google.client-id=",
                "sso.providers.google.client-secret=google-secret",
            ).run { context ->
                assertThat(context).hasFailed()
                assertThat(failureMessages(context)).contains("client-id")
            }
    }

    @Test
    fun enabledProviderWithBlankClientSecretFailsStartup() {
        contextRunner
            .withPropertyValues(
                "sso.callback-url=http://localhost:8080/api/v1/auth/sso/callback",
                "sso.providers.google.display-name=Google",
                "sso.providers.google.issuer-uri=https://accounts.google.com",
                "sso.providers.google.client-id=google-client",
                "sso.providers.google.client-secret=",
            ).run { context ->
                assertThat(context).hasFailed()
                assertThat(failureMessages(context)).contains("client-secret")
            }
    }

    @Test
    fun enabledProviderWithoutIssuerOrEndpointsFailsStartup() {
        contextRunner
            .withPropertyValues(
                "sso.callback-url=http://localhost:8080/api/v1/auth/sso/callback",
                "sso.providers.google.display-name=Google",
                "sso.providers.google.client-id=google-client",
                "sso.providers.google.client-secret=google-secret",
            ).run { context ->
                assertThat(context).hasFailed()
                assertThat(failureMessages(context)).contains("issuer-uri is absent")
            }
    }

    @Test
    fun enabledProviderWithPartiallyConfiguredEndpointsFailsStartup() {
        contextRunner
            .withPropertyValues(
                "sso.callback-url=http://localhost:8080/api/v1/auth/sso/callback",
                "sso.providers.google.display-name=Google",
                "sso.providers.google.client-id=google-client",
                "sso.providers.google.client-secret=google-secret",
                "sso.providers.google.authorization-uri=https://accounts.google.com/o/oauth2/auth",
            ).run { context ->
                assertThat(context).hasFailed()
                assertThat(failureMessages(context)).contains("token-uri")
            }
    }

    @Test
    fun invalidProviderIdFailsStartup() {
        contextRunner
            .withPropertyValues(
                "sso.callback-url=http://localhost:8080/api/v1/auth/sso/callback",
                "sso.providers.[Bad_Id].display-name=Bad",
            ).run { context ->
                assertThat(context).hasFailed()
                assertThat(failureMessages(context)).contains("provider id must match")
            }
    }

    @Test
    fun invalidEndpointUriFailsStartup() {
        contextRunner
            .withPropertyValues(
                "sso.callback-url=http://localhost:8080/api/v1/auth/sso/callback",
                "sso.providers.google.display-name=Google",
                "sso.providers.google.client-id=google-client",
                "sso.providers.google.client-secret=google-secret",
                "sso.providers.google.token-uri=not-a-uri",
            ).run { context ->
                assertThat(context).hasFailed()
                assertThat(failureMessages(context)).contains("token-uri must be an absolute http(s) URI")
            }
    }

    private fun failureMessages(context: AssertableApplicationContext): String =
        generateSequence<Throwable>(context.startupFailure) { it.cause }
            .joinToString(" | ") { it.message ?: "" }

    @EnableConfigurationProperties(SsoProperties::class)
    private class SsoPropertiesConfiguration
}
