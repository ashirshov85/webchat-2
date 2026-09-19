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
                // T042: the predefined providers are opt-in — the local dex IdP
                // and the dev-stand Google/Yandex IdPs — all disabled without
                // their SSO_*_ENABLED env, empty-secret defaults allowed by
                // the fail-fast validation (T007)
                assertThat(properties.providers.keys).containsExactly("dex", "google", "yandex")
                val dex = properties.providers.getValue("dex")
                assertThat(dex.enabled).isFalse()
                assertThat(dex.clientId).isEqualTo("webchat")
                assertThat(dex.clientSecret).isEmpty()
                assertThat(dex.trustedForEmailLinking).isTrue()
                assertThat(dex.authorizationUri).isEqualTo("http://localhost:5556/auth")
                assertThat(dex.tokenUri).isEqualTo("http://localhost:5556/token")
                // Google: declared by issuer only — endpoints come from lazy OIDC
                // discovery; enabled via SSO_GOOGLE_ENABLED + credentials env
                val google = properties.providers.getValue("google")
                assertThat(google.enabled).isFalse()
                assertThat(google.clientId).isEmpty()
                assertThat(google.clientSecret).isEmpty()
                assertThat(google.trustedForEmailLinking).isTrue()
                assertThat(google.issuerUri).isEqualTo("https://accounts.google.com")
                assertThat(google.scopes).containsExactly("openid", "email")
                // Yandex: same issuer-only shape as google — lazy discovery,
                // enabled via SSO_YANDEX_ENABLED + credentials env
                val yandex = properties.providers.getValue("yandex")
                assertThat(yandex.enabled).isFalse()
                assertThat(yandex.clientId).isEmpty()
                assertThat(yandex.clientSecret).isEmpty()
                assertThat(yandex.trustedForEmailLinking).isTrue()
                assertThat(yandex.issuerUri).isEqualTo("https://openid-connect.yandex.com")
                assertThat(yandex.scopes).containsExactly("openid", "email")
                // T054 (US6): `protocol` is not set for any predefined provider —
                // the default `oidc` keeps the dex/google/yandex behavior
                // unchanged (research §16, backward compatibility)
                listOf("dex", "google", "yandex").forEach { id ->
                    assertThat(properties.providers.getValue(id).protocol)
                        .isEqualTo(SsoProperties.Protocol.OIDC)
                }
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

    // T054 (US6, research §16–§18, FR-014/FR-015): protocol mode, claim
    // mapping and PKCE as first-class provider attributes with fail-fast
    // validation of the `oauth2-userinfo` shape

    @Test
    fun bindsProtocolAndClaimMappingDefaults() {
        contextRunner
            .withPropertyValues(
                "sso.callback-url=http://localhost:8080/api/v1/auth/sso/callback",
                "sso.providers.google.display-name=Google",
                "sso.providers.google.issuer-uri=https://accounts.google.com",
                "sso.providers.google.client-id=google-client",
                "sso.providers.google.client-secret=google-secret",
            ).run { context ->
                val provider = context.getBean(SsoProperties::class.java).providers.getValue("google")

                assertThat(provider.protocol).isEqualTo(SsoProperties.Protocol.OIDC)
                assertThat(provider.subjectClaim).isEqualTo("sub")
                assertThat(provider.emailClaim).isEqualTo("email")
                assertThat(provider.emailVerifiedMode)
                    .isEqualTo(SsoProperties.EmailVerifiedMode.CLAIM)
                assertThat(provider.pkce).isTrue()
            }
    }

    @Test
    fun bindsOauth2UserinfoProviderAttributes() {
        contextRunner
            .withPropertyValues(
                "sso.callback-url=http://localhost:8080/api/v1/auth/sso/callback",
                "sso.providers.ya.display-name=Yandex",
                "sso.providers.ya.protocol=oauth2-userinfo",
                "sso.providers.ya.client-id=ya-client",
                "sso.providers.ya.client-secret=ya-secret",
                "sso.providers.ya.authorization-uri=https://oauth.yandex.ru/authorize",
                "sso.providers.ya.token-uri=https://oauth.yandex.ru/token",
                "sso.providers.ya.userinfo-uri=https://login.yandex.ru/info",
                "sso.providers.ya.subject-claim=psuid",
                "sso.providers.ya.email-claim=default_email",
                "sso.providers.ya.email-verified-mode=provider-guaranteed",
                "sso.providers.ya.pkce=false",
            ).run { context ->
                assertThat(context).hasNotFailed()
                val provider = context.getBean(SsoProperties::class.java).providers.getValue("ya")

                assertThat(provider.protocol)
                    .isEqualTo(SsoProperties.Protocol.OAUTH2_USERINFO)
                assertThat(provider.subjectClaim).isEqualTo("psuid")
                assertThat(provider.emailClaim).isEqualTo("default_email")
                assertThat(provider.emailVerifiedMode)
                    .isEqualTo(SsoProperties.EmailVerifiedMode.PROVIDER_GUARANTEED)
                assertThat(provider.pkce).isFalse()
            }
    }

    @Test
    fun enabledOauth2UserinfoProviderWithoutIssuerOrJwksStarts() {
        // issuer-uri/jwks-uri are OIDC-only: for oauth2-userinfo the profile
        // comes from the userinfo endpoint, so they are not required
        contextRunner
            .withPropertyValues(
                "sso.callback-url=http://localhost:8080/api/v1/auth/sso/callback",
                "sso.providers.ya.display-name=Yandex",
                "sso.providers.ya.protocol=oauth2-userinfo",
                "sso.providers.ya.client-id=ya-client",
                "sso.providers.ya.client-secret=ya-secret",
                "sso.providers.ya.authorization-uri=https://oauth.yandex.ru/authorize",
                "sso.providers.ya.token-uri=https://oauth.yandex.ru/token",
                "sso.providers.ya.userinfo-uri=https://login.yandex.ru/info",
            ).run { context ->
                assertThat(context).hasNotFailed()
                val provider = context.getBean(SsoProperties::class.java).providers.getValue("ya")

                assertThat(provider.protocol)
                    .isEqualTo(SsoProperties.Protocol.OAUTH2_USERINFO)
            }
    }

    @Test
    fun enabledOauth2UserinfoProviderWithIssuerOnlyFailsStartup() {
        // issuer-uri lazy discovery does not satisfy oauth2-userinfo:
        // explicit authorization/token/userinfo endpoints are mandatory
        // (research §16 — issuer is ignored in this mode)
        contextRunner
            .withPropertyValues(
                "sso.callback-url=http://localhost:8080/api/v1/auth/sso/callback",
                "sso.providers.ya.display-name=Yandex",
                "sso.providers.ya.protocol=oauth2-userinfo",
                "sso.providers.ya.client-id=ya-client",
                "sso.providers.ya.client-secret=ya-secret",
                "sso.providers.ya.issuer-uri=https://openid-connect.yandex.com",
            ).run { context ->
                assertThat(context).hasFailed()
                assertThat(failureMessages(context)).contains("authorization-uri")
            }
    }

    @Test
    fun enabledOauth2UserinfoProviderWithoutUserinfoUriFailsStartup() {
        // the profile leg is mandatory: userinfo-uri is required even when
        // the OIDC-style jwks-uri is present
        contextRunner
            .withPropertyValues(
                "sso.callback-url=http://localhost:8080/api/v1/auth/sso/callback",
                "sso.providers.ya.display-name=Yandex",
                "sso.providers.ya.protocol=oauth2-userinfo",
                "sso.providers.ya.client-id=ya-client",
                "sso.providers.ya.client-secret=ya-secret",
                "sso.providers.ya.authorization-uri=https://oauth.yandex.ru/authorize",
                "sso.providers.ya.token-uri=https://oauth.yandex.ru/token",
                "sso.providers.ya.jwks-uri=https://login.yandex.ru/jwks",
            ).run { context ->
                assertThat(context).hasFailed()
                assertThat(failureMessages(context)).contains("userinfo-uri")
            }
    }

    @Test
    fun enabledOauth2UserinfoProviderWithPkceFalseAndBlankSecretFailsStartup() {
        // FR-015: pkce=false only drops the code_challenge leg — the
        // confidential code exchange still requires client-secret
        contextRunner
            .withPropertyValues(
                "sso.callback-url=http://localhost:8080/api/v1/auth/sso/callback",
                "sso.providers.ya.display-name=Yandex",
                "sso.providers.ya.protocol=oauth2-userinfo",
                "sso.providers.ya.client-id=ya-client",
                "sso.providers.ya.client-secret=",
                "sso.providers.ya.authorization-uri=https://oauth.yandex.ru/authorize",
                "sso.providers.ya.token-uri=https://oauth.yandex.ru/token",
                "sso.providers.ya.userinfo-uri=https://login.yandex.ru/info",
                "sso.providers.ya.pkce=false",
            ).run { context ->
                assertThat(context).hasFailed()
                assertThat(failureMessages(context)).contains("client-secret")
            }
    }

    @Test
    fun disabledOauth2UserinfoProviderWithEmptySecretStarts() {
        // env opt-in (T042 pattern): a yandex-shaped provider stays valid
        // while disabled even with pkce=false and an empty secret
        // placeholder — startup without SSO_* env must not fail fast
        contextRunner
            .withPropertyValues(
                "sso.callback-url=http://localhost:8080/api/v1/auth/sso/callback",
                "sso.providers.ya.display-name=Yandex",
                "sso.providers.ya.enabled=false",
                "sso.providers.ya.protocol=oauth2-userinfo",
                "sso.providers.ya.client-id=ya-client",
                "sso.providers.ya.client-secret=",
                "sso.providers.ya.authorization-uri=https://oauth.yandex.ru/authorize",
                "sso.providers.ya.token-uri=https://oauth.yandex.ru/token",
                "sso.providers.ya.userinfo-uri=https://login.yandex.ru/info",
                "sso.providers.ya.pkce=false",
            ).run { context ->
                assertThat(context).hasNotFailed()
                val provider = context.getBean(SsoProperties::class.java).providers.getValue("ya")

                assertThat(provider.enabled).isFalse()
                assertThat(provider.clientSecret).isEmpty()
                assertThat(provider.pkce).isFalse()
            }
    }

    @Test
    fun invalidProtocolValueFailsStartup() {
        // only `oidc | oauth2-userinfo` are supported (research §16)
        contextRunner
            .withPropertyValues(
                "sso.callback-url=http://localhost:8080/api/v1/auth/sso/callback",
                "sso.providers.ya.display-name=Yandex",
                "sso.providers.ya.protocol=saml",
                "sso.providers.ya.client-id=ya-client",
                "sso.providers.ya.client-secret=ya-secret",
                "sso.providers.ya.issuer-uri=https://openid-connect.yandex.com",
            ).run { context ->
                assertThat(context).hasFailed()
                assertThat(failureMessages(context)).contains("protocol")
            }
    }

    private fun failureMessages(context: AssertableApplicationContext): String =
        generateSequence<Throwable>(context.startupFailure) { it.cause }
            .joinToString(" | ") { it.message ?: "" }

    @EnableConfigurationProperties(SsoProperties::class)
    private class SsoPropertiesConfiguration
}
