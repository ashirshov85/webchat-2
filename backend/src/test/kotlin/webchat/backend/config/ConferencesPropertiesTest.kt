package webchat.backend.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.ConfigurationPropertiesBindException
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.Duration

class ConferencesPropertiesTest {
    private val contextRunner =
        ApplicationContextRunner()
            .withUserConfiguration(ConferencesPropertiesConfiguration::class.java)

    @Test
    fun bindsQ19DefaultsFromConfiguration() {
        contextRunner
            .withPropertyValues(*Q19_DEFAULTS)
            .run { context ->
                val properties = context.getBean(ConferencesProperties::class.java)

                assertThat(properties.maxParticipants).isEqualTo(16)
                assertThat(properties.tokenTtl).isEqualTo(Duration.ofSeconds(900))
                assertThat(properties.guestInviteTtl).isEqualTo(Duration.ofHours(24))
                assertThat(properties.emptyRoomTimeout).isEqualTo(Duration.ofSeconds(60))
                assertThat(properties.departureTimeout).isEqualTo(Duration.ofSeconds(60))
                assertThat(properties.maxDuration).isEqualTo(Duration.ofHours(4))
                assertThat(properties.warningBeforeEnd).isEqualTo(Duration.ofMinutes(5))
                assertThat(properties.reconnectWindow).isEqualTo(Duration.ofSeconds(120))
                assertThat(properties.guestNameMaxLength).isEqualTo(50)
                assertThat(properties.sweepInterval).isEqualTo(Duration.ofSeconds(60))
                assertThat(properties.roomLostGrace).isEqualTo(Duration.ofSeconds(120))
                assertThat(properties.livekit.url).isEqualTo("http://localhost:7880")
                assertThat(properties.livekit.apiKey).isEqualTo("devkey")
                assertThat(properties.livekit.apiSecret).isEqualTo("secret")
                // no split by default: the client WS ingress inherits `url`
                // (local-compose single-URL setup)
                assertThat(properties.livekit.publicUrl).isEmpty()
                assertThat(properties.livekit.clientUrl()).isEqualTo("http://localhost:7880")
                // T047: the per-IP bucket specs of the public guest pair
                // №54/№55 (api-contract.md 009 §3 canon)
                assertThat(properties.rateLimit.inviteInfo).isEqualTo("30/1m")
                assertThat(properties.rateLimit.joinByInvite).isEqualTo("10/1m")
                // T053: the LiveKit webhook intake bucket (api-contract.md
                // 009 §3 canon — generous for delivery retries)
                assertThat(properties.rateLimit.livekitWebhook).isEqualTo("60/1m")
            }
    }

    @Test
    fun bindsPerEnvOverrides() {
        // quickstart.md §2: per-env overrides win over the Q19 defaults —
        // the lowered timeout profile (SPRING_APPLICATION_JSON accepts the
        // same ISO-8601 PT-forms) plus the real LiveKit env pair
        contextRunner
            .withPropertyValues(
                "conferences.max-participants=3",
                "conferences.token-ttl=30s",
                "conferences.guest-invite-ttl=PT1H",
                "conferences.empty-room-timeout=10s",
                "conferences.departure-timeout=15s",
                "conferences.max-duration=PT2M",
                "conferences.warning-before-end=PT30S",
                "conferences.reconnect-window=60s",
                "conferences.guest-name-max-length=10",
                "conferences.sweep-interval=5s",
                "conferences.room-lost-grace=30s",
                "conferences.livekit.url=http://livekit:7880",
                "conferences.livekit.api-key=prodkey",
                "conferences.livekit.api-secret=prodsecret",
                "conferences.livekit.public-url=https://dev.webchat.lkshr.ru/livekit",
                "conferences.rate-limit.invite-info=60/1m",
                "conferences.rate-limit.join-by-invite=20/1m",
                "conferences.rate-limit.livekit-webhook=120/1m",
            ).run { context ->
                val properties = context.getBean(ConferencesProperties::class.java)

                assertThat(properties.maxParticipants).isEqualTo(3)
                assertThat(properties.tokenTtl).isEqualTo(Duration.ofSeconds(30))
                assertThat(properties.guestInviteTtl).isEqualTo(Duration.ofHours(1))
                assertThat(properties.emptyRoomTimeout).isEqualTo(Duration.ofSeconds(10))
                assertThat(properties.departureTimeout).isEqualTo(Duration.ofSeconds(15))
                assertThat(properties.maxDuration).isEqualTo(Duration.ofMinutes(2))
                assertThat(properties.warningBeforeEnd).isEqualTo(Duration.ofSeconds(30))
                assertThat(properties.reconnectWindow).isEqualTo(Duration.ofSeconds(60))
                assertThat(properties.guestNameMaxLength).isEqualTo(10)
                assertThat(properties.sweepInterval).isEqualTo(Duration.ofSeconds(5))
                assertThat(properties.roomLostGrace).isEqualTo(Duration.ofSeconds(30))
                assertThat(properties.livekit.url).isEqualTo("http://livekit:7880")
                assertThat(properties.livekit.apiKey).isEqualTo("prodkey")
                assertThat(properties.livekit.apiSecret).isEqualTo("prodsecret")
                // the dev-stand split: in-cluster Twirp ingress for the admin
                // adapter, public ingress route for browser JoinTickets
                assertThat(properties.livekit.publicUrl).isEqualTo("https://dev.webchat.lkshr.ru/livekit")
                assertThat(properties.livekit.clientUrl()).isEqualTo("https://dev.webchat.lkshr.ru/livekit")
                // T047 per-env overrides — the k6 flood profile of T058 rides
                // exactly these knobs (2× the default buckets)
                assertThat(properties.rateLimit.inviteInfo).isEqualTo("60/1m")
                assertThat(properties.rateLimit.joinByInvite).isEqualTo("20/1m")
                assertThat(properties.rateLimit.livekitWebhook).isEqualTo("120/1m")
            }
    }

    @Test
    fun missingKeyFailsStartup() {
        contextRunner.run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).isInstanceOf(ConfigurationPropertiesBindException::class.java)
        }
    }

    @EnableConfigurationProperties(ConferencesProperties::class)
    private class ConferencesPropertiesConfiguration

    private companion object {
        /** The Q19 application.yml block, canonical property names (T005). */
        val Q19_DEFAULTS =
            arrayOf(
                "conferences.max-participants=16",
                "conferences.token-ttl=900s",
                "conferences.guest-invite-ttl=24h",
                "conferences.empty-room-timeout=60s",
                "conferences.departure-timeout=60s",
                "conferences.max-duration=4h",
                "conferences.warning-before-end=5m",
                "conferences.reconnect-window=120s",
                "conferences.guest-name-max-length=50",
                "conferences.sweep-interval=60s",
                "conferences.room-lost-grace=120s",
                "conferences.livekit.url=http://localhost:7880",
                "conferences.livekit.api-key=devkey",
                "conferences.livekit.api-secret=secret",
                "conferences.livekit.public-url=",
                "conferences.rate-limit.invite-info=30/1m",
                "conferences.rate-limit.join-by-invite=10/1m",
                "conferences.rate-limit.livekit-webhook=60/1m",
            )
    }
}
