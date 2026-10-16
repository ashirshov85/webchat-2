package webchat.backend.conferences.livekit

import com.fasterxml.jackson.databind.ObjectMapper
import io.livekit.server.RoomServiceClient
import livekit.LivekitModels.DataPacket
import livekit.LivekitModels.ParticipantPermission
import livekit.LivekitModels.TrackSource
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import retrofit2.Call
import retrofit2.Response
import webchat.backend.conferences.domain.model.PublicationSource
import webchat.backend.conferences.domain.model.PublishGrants
import webchat.backend.conferences.domain.port.ConferenceMediaUnavailableException
import webchat.backend.conferences.domain.port.LiveKitAdminClient
import webchat.backend.conferences.domain.port.LiveKitParticipant
import webchat.backend.config.ConferencesProperties
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * The [LiveKitAdminClient] adapter over the official LiveKit Kotlin SDK
 * (research §3, livekit-integration.md §4 — DIP: the domain port stays
 * technology-free, the SDK never leaks past this class).
 *
 * **Admin token** (§3): the held [RoomServiceClient] mints a fresh
 * server JWT per call IN MEMORY with exactly the `roomCreate` +
 * `roomList` + `roomAdmin` grants (per-method `RoomName` scoping is
 * added by the SDK itself) — the token never leaves the backend and is
 * never issued to a client (FR-008).
 *
 * **Retry + backoff** (§4 «Идемпотентность»): every Twirp call is
 * repeated on transient failures only — network [IOException], HTTP
 * `429` and `5xx` — with exponential backoff
 * ([retryBackoff] × 1, 2, … up to [maxAttempts] tries). A plane that
 * keeps failing surfaces as [ConferenceMediaUnavailableException]
 * (→ `503 conference_media_unavailable`, T017/T021); every other
 * outcome is terminal and never retried.
 *
 * **SET-semantics** (§4): a logically idempotent outcome — a room
 * already gone (`DeleteRoom` after a webhook/sweeper race), an
 * identity not connected (`RemoveParticipant`/`UpdateParticipant`
 * after a self-disconnect) — resolves as a SUCCESS no-op via the
 * Twirp `not_found` classification (HTTP 404 or the `not_found`
 * error code); an existing `CreateRoom` target is updated, never
 * duplicated (the sweeper's self-heal rides the same call).
 */
@Component
@Suppress("TooManyFunctions") // the 8 port methods of §4 + the adapter's single retry/mapping plumbing
class LiveKitServerAdminClient
    internal constructor(
        private val objectMapper: ObjectMapper,
        private val client: RoomServiceClient,
        private val retryBackoff: Duration,
        private val maxAttempts: Int,
    ) : LiveKitAdminClient {
        @Autowired
        constructor(
            properties: ConferencesProperties,
            objectMapper: ObjectMapper,
        ) : this(
            objectMapper = objectMapper,
            client =
                RoomServiceClient.createClient(
                    properties.livekit.url,
                    properties.livekit.apiKey,
                    properties.livekit.apiSecret,
                ),
            retryBackoff = DEFAULT_RETRY_BACKOFF,
            maxAttempts = DEFAULT_MAX_ATTEMPTS,
        )

        /**
         * `CreateRoom` (§2): the eager room of №44 and the sweeper's
         * re-creation — an existing room is updated, never duplicated.
         */
        override fun createRoom(
            name: String,
            emptyTimeout: Duration,
            departureTimeout: Duration,
            maxParticipants: Int,
        ) {
            // both outcomes are success: SET-semantics — the existing
            // room configuration is simply re-asserted
            twirp {
                client.createRoom(
                    name = name,
                    emptyTimeout = emptyTimeout.toLiveKitSeconds(),
                    maxParticipants = maxParticipants,
                    departureTimeout = departureTimeout.toLiveKitSeconds(),
                )
            }
        }

        /** `DeleteRoom` (§4): the controlled end — an absent room is the idempotent no-op (webhook/sweeper race). */
        override fun deleteRoom(name: String) {
            twirp { client.deleteRoom(name) }
        }

        /** `ListRooms` (§4): the bare room-name set — the №44 pre-flight probe and the sweeper's existence scan. */
        override fun listRoomNames(): Set<String> =
            when (val outcome = twirp { client.listRooms() }) {
                is TwirpOutcome.Success -> outcome.body?.mapTo(HashSet()) { it.name } ?: emptySet()
                TwirpOutcome.TargetAbsent -> emptySet()
            }

        /**
         * `ListParticipants` (§4): the live connection truth — quota
         * pre-checks, invite revocation, chat-removal disconnect.
         */
        override fun listParticipants(room: String): List<LiveKitParticipant> =
            when (val outcome = twirp { client.listParticipants(room) }) {
                is TwirpOutcome.Success ->
                    outcome.body.orEmpty().map { info ->
                        // proto3 keeps unset metadata as "" — the port
                        // contract maps that to null (§3: metadata or
                        // `null` when unset)
                        LiveKitParticipant(
                            identity = info.identity,
                            metadata = info.metadata.takeUnless(String::isEmpty),
                        )
                    }
                TwirpOutcome.TargetAbsent -> emptyList()
            }

        /** `RemoveParticipant` (§4): one connection broken server-side — an absent identity is the idempotent no-op. */
        override fun removeParticipant(
            room: String,
            identity: String,
        ) {
            twirp { client.removeParticipant(room, identity) }
        }

        /**
         * `UpdateParticipant` (§4): the full SET of a live connection's
         * publish permission and metadata — the immediate moderation
         * application (T036). The adapter adds the contract constants
         * (`canSubscribe=true`, `canPublishData=false`, §3) around the
         * domain-computed [PublishGrants].
         */
        override fun updateParticipant(
            room: String,
            identity: String,
            permission: PublishGrants,
            metadata: String,
        ) {
            twirp {
                client.updateParticipant(
                    room,
                    identity,
                    null,
                    metadata,
                    permission.toWirePermission(),
                    null,
                )
            }
        }

        /**
         * `MutePublishedTrack` (§4): the adapter resolves the source's
         * live track sids itself — the ALREADY PUBLISHED, NOT yet muted
         * tracks of [source] of the connection are muted; a muted or
         * absent track (or an absent identity/room) is a no-op
         * (SET-semantics keeps repeated moderation idempotent).
         */
        override fun mutePublishedTrack(
            room: String,
            identity: String,
            source: PublicationSource,
        ) {
            val target =
                when (val outcome = twirp { client.listParticipants(room) }) {
                    is TwirpOutcome.Success -> outcome.body?.firstOrNull { it.identity == identity } ?: return
                    TwirpOutcome.TargetAbsent -> return
                }
            target.tracksList
                .filter { it.source == source.toWire() && !it.muted }
                .forEach { track -> twirp { client.mutePublishedTrack(room, identity, track.sid, true) } }
        }

        /**
         * `SendData` (§4): a RELIABLE packet to EVERY participant (empty
         * destination set) — the max-duration warning reaches connected
         * guests that have no SSE (Q16=B); [payload] is encoded for the
         * wire here.
         */
        override fun sendData(
            room: String,
            topic: String,
            payload: String,
        ) {
            twirp {
                client.sendData(
                    room,
                    payload.toByteArray(StandardCharsets.UTF_8),
                    DataPacket.Kind.RELIABLE,
                    emptyList(),
                    emptyList(),
                    topic,
                )
            }
        }

        /**
         * The single Twirp execution primitive every port method rides:
         * [maxAttempts] tries, transient failures ([IOException], HTTP
         * 429/5xx) backed off exponentially, `not_found` folded into
         * [TwirpOutcome.TargetAbsent], a non-transient client error
         * (misconfiguration — e.g. a bad API pair answering 401/403)
         * failing FAST as a loud [TwirpFailure] instead of a dishonest
         * «unavailable», and exhausted retries surfacing as
         * [ConferenceMediaUnavailableException].
         */
        @Suppress("NestedBlockDepth") // the retry classification IS the nesting: attempt → answer → terminal kind
        private fun <T> twirp(callFactory: () -> Call<T>): TwirpOutcome<T> {
            var lastFailure: Exception? = null
            var loudFailure: TwirpFailure? = null
            run twirpAttempts@{
                for (attempt in 1..maxAttempts) {
                    if (attempt > 1 && !backoffBefore(attempt)) return@twirpAttempts
                    try {
                        val response = callFactory().execute()
                        if (response.isSuccessful) return TwirpOutcome.Success(response.body())
                        val failure = response.asFailure()
                        when {
                            failure.absent -> return TwirpOutcome.TargetAbsent
                            failure.transient -> lastFailure = failure
                            else ->
                                // non-transient — the misconfiguration
                                // fails fast, never retried, never
                                // «unavailable»
                                loudFailure = failure
                        }
                    } catch (failure: IOException) {
                        // connection refused / reset / timeout — the
                        // media plane answer that IS retried
                        lastFailure = failure
                    }
                    if (loudFailure != null) return@twirpAttempts
                    if (attempt < maxAttempts) {
                        log.warn(
                            "LiveKit Twirp call failed (attempt {}/{}), retrying: {}",
                            attempt,
                            maxAttempts,
                            lastFailure?.message,
                        )
                    }
                }
            }
            throw loudFailure ?: ConferenceMediaUnavailableException(
                lastFailure ?: error("the retry loop must record a failure before aborting"),
            )
        }

        /**
         * Exponential sleep before re-attempt N: [retryBackoff] ×
         * 2^(N-2); an interrupt aborts the retry loop as «unavailable».
         */
        private fun backoffBefore(attempt: Int): Boolean =
            try {
                val shift = (attempt - 2).coerceIn(0, MAX_BACKOFF_SHIFT)
                Thread.sleep(retryBackoff.multipliedBy(1L shl shift).toMillis())
                true
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }

        /** Parses the Twirp JSON error body (single read) into the [TwirpFailure] classification. */
        private fun <T> Response<T>.asFailure(): TwirpFailure {
            val raw =
                runCatching { errorBody()?.string() }.getOrNull()
            val twirpCode =
                runCatching {
                    raw
                        ?.let { objectMapper.readValue(it, Map::class.java) }
                        ?.get(TWIRP_CODE_FIELD) as? String
                }.getOrNull()
            return TwirpFailure(response = this, twirpCode = twirpCode)
        }

        /** `canSubscribe`/`canPublishData` are the §3 contract constants; only the publish side is domain-computed. */
        private fun PublishGrants.toWirePermission(): ParticipantPermission =
            ParticipantPermission
                .newBuilder()
                .setCanSubscribe(true)
                .setCanPublish(canPublish)
                .setCanPublishData(false)
                .addAllCanPublishSources(canPublishSources.map { it.toWire() })
                .build()

        private fun PublicationSource.toWire(): TrackSource =
            when (this) {
                PublicationSource.MICROPHONE -> TrackSource.MICROPHONE
                PublicationSource.CAMERA -> TrackSource.CAMERA
                PublicationSource.SCREEN_SHARE -> TrackSource.SCREEN_SHARE
                PublicationSource.SCREEN_SHARE_AUDIO -> TrackSource.SCREEN_SHARE_AUDIO
            }

        /** LiveKit room timers are integral seconds; sub-second configured values round up to the 1 s floor. */
        private fun Duration.toLiveKitSeconds(): Int = maxOf(1L, toSeconds()).toInt()

        /** The terminal outcomes of the retry loop: a successful body, or the idempotent `not_found` no-op target. */
        private sealed interface TwirpOutcome<out T> {
            data class Success<out T>(
                val body: T?,
            ) : TwirpOutcome<T>

            data object TargetAbsent : TwirpOutcome<Nothing>
        }

        /**
         * A non-2xx Twirp answer: [absent] (HTTP 404 / code `not_found`)
         * is the idempotent no-op target, [transient] (429 / 5xx) is the
         * retried shape; everything else is a loud fast failure — a
         * server-side misconfiguration, never a client-facing problem.
         */
        private class TwirpFailure(
            response: Response<*>,
            val twirpCode: String?,
        ) : RuntimeException(
                "LiveKit Twirp call failed: HTTP ${response.code()}" +
                    (twirpCode?.let { ", code $it" } ?: ""),
            ) {
            private val httpCode = response.code()

            val absent: Boolean get() = httpCode == HTTP_NOT_FOUND || twirpCode == TWIRP_NOT_FOUND

            val transient: Boolean get() = httpCode == HTTP_TOO_MANY_REQUESTS || httpCode >= HTTP_SERVER_ERROR_FLOOR
        }

        private companion object {
            private val log = LoggerFactory.getLogger(LiveKitServerAdminClient::class.java)

            /**
             * research §3: Twirp retries cover the transient blips only —
             * 3 tries keep the SC-005 budget (≤ 2 s) intact.
             */
            private const val DEFAULT_MAX_ATTEMPTS = 3

            /** 150 ms × 1, 2 — worst-case added latency 450 ms before the honest `503`. */
            private val DEFAULT_RETRY_BACKOFF = Duration.ofMillis(150)

            /** Twirp `not_found` rides HTTP 404, but the error-body code is the plane's own truth. */
            private const val HTTP_NOT_FOUND = 404

            private const val HTTP_TOO_MANY_REQUESTS = 429

            private const val HTTP_SERVER_ERROR_FLOOR = 500

            /** caps the exponential shift — the backoff ceiling. */
            private const val MAX_BACKOFF_SHIFT = 4

            private const val TWIRP_CODE_FIELD = "code"

            private const val TWIRP_NOT_FOUND = "not_found"
        }
    }
