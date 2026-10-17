package webchat.backend.conferences.livekit

import io.livekit.server.AccessToken
import io.livekit.server.CanPublish
import io.livekit.server.CanPublishData
import io.livekit.server.CanPublishSources
import io.livekit.server.CanSubscribe
import io.livekit.server.RoomJoin
import io.livekit.server.RoomName
import org.springframework.stereotype.Component
import webchat.backend.conferences.domain.model.PublicationSource
import webchat.backend.conferences.domain.port.ClientTokenRequest
import webchat.backend.conferences.domain.port.LiveKitTokenMinter
import webchat.backend.config.ConferencesProperties
import java.time.Duration

/**
 * The [LiveKitTokenMinter] adapter over the official LiveKit Kotlin SDK
 * `AccessToken` (research §3, livekit-integration.md §3 — DIP: the SDK
 * never leaks past the `conferences.livekit` adapters, the same seam as
 * [LiveKitServerAdminClient]).
 *
 * Signs the CLIENT token of №47/№55 locally (HS256,
 * `iss = LIVEKIT_API_KEY` from [ConferencesProperties.livekit] — the
 * pair the media plane itself validates) with EXACTLY the §3 claim
 * table: `sub` = the full per-connection identity, `name`, `metadata`,
 * `video: {roomJoin, room, canSubscribe, canPublish,
 * canPublishSources, canPublishData}`. The §3 constants
 * (`roomJoin=true`, `canSubscribe=true`, `canPublishData=false` — v1
 * has no participant-to-participant data) are added HERE around the
 * domain-computed
 * [webchat.backend.conferences.domain.model.PublishGrants]; admin
 * grants (`roomAdmin/roomCreate/roomList/roomRecord/ingressAdmin`) are
 * NEVER set — a client token is a join ticket, all moderation stays
 * server-side (FR-008).
 */
@Component
class LiveKitServerTokenMinter(
    properties: ConferencesProperties,
) : LiveKitTokenMinter {
    private val apiKey = properties.livekit.apiKey
    private val apiSecret = properties.livekit.apiSecret

    override fun mint(request: ClientTokenRequest): String =
        AccessToken(apiKey, apiSecret)
            .apply {
                identity = request.identity
                name = request.name
                metadata = request.metadata
                ttl = request.ttl.toLiveKitTtlMillis()
                addGrants(
                    RoomJoin(true),
                    RoomName(request.roomName),
                    CanSubscribe(true),
                    CanPublish(request.grants.canPublish),
                    CanPublishSources(request.grants.canPublishSources.map { it.wireName() }),
                    CanPublishData(false),
                )
            }.toJwt()

    /** The §3 wire names of the publishable sources (`mic_restricted` removes `microphone`, etc.). */
    private fun PublicationSource.wireName(): String =
        when (this) {
            PublicationSource.MICROPHONE -> "microphone"
            PublicationSource.CAMERA -> "camera"
            PublicationSource.SCREEN_SHARE -> "screen_share"
            PublicationSource.SCREEN_SHARE_AUDIO -> "screen_share_audio"
        }

    /**
     * The SDK TTL unit is MILLISECONDS (`AccessToken.ttl` — the
     * constructor default reads `TimeUnit.MILLISECONDS.convert(6,
     * HOURS)`, verified against 0.10.0 bytecode), and the underlying
     * auth0 signer floors `exp` to the whole-second grid — sub-second
     * configured values round up to the 1 s floor so a token is never
     * born expired.
     */
    private fun Duration.toLiveKitTtlMillis(): Long = maxOf(MIN_TTL_MILLIS, toMillis())

    private companion object {
        const val MIN_TTL_MILLIS = 1000L
    }
}
