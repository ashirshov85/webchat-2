package webchat.backend.realtime

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * The user event stream of contract №18 (T018; realtime-channel.md §1,
 * FR-022): `GET /api/v1/users/me/events` opens ONE strictly
 * server→client SSE channel per device/session (US2-6 multi-device),
 * multiplexing the events of all dialogs of the token owner. Sending is
 * NOT this channel's job — POST /chats/{id}/messages (№16) answers with
 * the durable record itself.
 *
 * The security chain has ALREADY authenticated the request — the same
 * Bearer gate as every REST operation of the contract; the owner id is
 * the token `sub` claim, exactly like ChatController (T016). The token
 * travels only in the `Authorization` header, never in the URL
 * (constitution V).
 *
 * The adapter answers `200 text/event-stream` with `X-Accel-Buffering:
 * no` — no proxy aggregation behind ingress (research.md 004 §1; the
 * ingress route annotations are T020) — and writes the opening
 * `retry: 3000` frame BEFORE the emitter is registered, so it is
 * guaranteed to precede any heartbeat (`:ka`, [SseConnectionRegistry])
 * or event frame of a concurrent dispatch. The emitter never times out
 * server-side (0): liveness is the heartbeat's job and the client
 * reconnect is the normal mode (FR-009, research.md 004 §1 — proxy
 * timeouts ≥ 5 min are the ingress contract, not the emitter's).
 *
 * 007 (T013, contracts/presence-events.md §1): the stream now opens
 * with a SECOND opening frame — `event: connected` carrying the
 * server-minted `connectionId` of this registration — written right
 * after `retry: 3000` and also BEFORE the registration, so the frame
 * order of the contract is exact: retry → connected → (events). The
 * frame is additive for old consumers (unknown `event:` is ignored,
 * realtime-channel.md §3) and becomes the id the client heartbeat №37
 * renews. The same minted id drives the [PresenceConnectionLifecycle]
 * hooks: registration on open, unregister on close/error — best-effort
 * with ids-only warns, because the №18 channel's own delivery duty
 * must survive a degraded presence store (a failed registration
 * self-heals through the №37 404 → SSE reconnect path,
 * presence-api.md §2; a failed unregister through the TTL expiry,
 * FR-002).
 */
@RestController
@RequestMapping("/api/v1/users/me")
class RealtimeController(
    private val connectionRegistry: SseConnectionRegistry,
    private val presenceLifecycle: ObjectProvider<PresenceConnectionLifecycle>,
) {
    private val log = LoggerFactory.getLogger(RealtimeController::class.java)

    /**
     * Contract №18: the per-user SSE stream — see the class KDoc. The
     * emitter's lifecycle callbacks keep the registry exact: completion,
     * error and timeout all funnel into the unregister of this session —
     * and, since 007 T013, into the presence unregister of the minted
     * connectionId (a repeat is a no-op by the port contract).
     */
    @GetMapping("/events", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun streamUserEvents(
        @AuthenticationPrincipal accessToken: Jwt,
    ): ResponseEntity<SseEmitter> {
        val userId = UUID.fromString(accessToken.subject)
        // data-model 007 §1.1: the registration carries the 002 session of
        // its access token (AuthJwtDecoder always sets `sid` on a verified
        // token — the claim is as guaranteed as `sub` here).
        val sessionId = UUID.fromString(accessToken.getClaimAsString(SESSION_ID_CLAIM))
        val connectionId = UUID.randomUUID()
        val emitter = SseEmitter(NEVER_TIME_OUT)
        emitter.onCompletion {
            connectionRegistry.unregister(userId, emitter)
            notifyPresenceClosed(userId, connectionId)
        }
        emitter.onTimeout { emitter.complete() }
        emitter.onError {
            connectionRegistry.unregister(userId, emitter)
            notifyPresenceClosed(userId, connectionId)
        }
        // The Set overload of ResponseBodyEmitter writes items VERBATIM —
        // the Object overloads of SseEmitter would wrap the text as `data:`
        // lines (realtime-channel.md §2 spells the retry field, not data).
        emitter.send(
            setOf(ResponseBodyEmitter.DataWithMediaType("retry: $RECONNECT_HINT_MILLIS\n\n", FRAME_MEDIA_TYPE)),
        )
        // presence-events.md §1: the `connected` opening frame right after
        // `retry: 3000`, still before the registration — the same verbatim
        // discipline and ordering guarantee as the retry frame above.
        emitter.send(
            setOf(ResponseBodyEmitter.DataWithMediaType(connectedFrame(connectionId), FRAME_MEDIA_TYPE)),
        )
        connectionRegistry.register(userId, emitter)
        notifyPresenceOpened(userId, sessionId, connectionId)
        return ResponseEntity
            .ok()
            .header(PROXY_BUFFERING_HEADER, PROXY_BUFFERING_OFF)
            .body(emitter)
    }

    /**
     * presence-events.md §1: `event: connected` + ONE `data:` line with
     * the single-field `ConnectedEvent` payload of contracts/openapi.yaml.
     * A UUID renders with [0-9a-f-] only, so the interpolation into the
     * single-line JSON needs no escaping ([SseConnectionRegistry.eventFrame]
     * discipline: one `data:` line per frame).
     */
    private fun connectedFrame(connectionId: UUID): String =
        "event: $CONNECTED_EVENT\ndata: {\"$CONNECTION_ID_FIELD\":\"$connectionId\"}\n\n"

    /**
     * The presence open hook runs AFTER the frames and the registry
     * registration — the №18 stream must open even while presence is
     * degraded, so the hook is best-effort: a failure is a warn with ids
     * only (constitution V) and the client converges through the №37
     * 404 → reconnect path (presence-api.md §2).
     */
    private fun notifyPresenceOpened(
        userId: UUID,
        sessionId: UUID,
        connectionId: UUID,
    ) = notifyPresence("registration of connection <$connectionId> of user <$userId>") {
        presenceLifecycle.ifAvailable?.onConnectionOpened(userId, sessionId, connectionId)
    }

    /** The close/error counterpart of [notifyPresenceOpened] — the emitter lifecycle callbacks. */
    private fun notifyPresenceClosed(
        userId: UUID,
        connectionId: UUID,
    ) = notifyPresence("unregistration of connection <$connectionId> of user <$userId>") {
        presenceLifecycle.ifAvailable?.onConnectionClosed(userId, connectionId)
    }

    @Suppress("TooGenericExceptionCaught") // a degraded presence store signals itself by throwing
    private fun notifyPresence(
        what: String,
        call: () -> Unit,
    ) {
        try {
            call()
        } catch (failure: Exception) {
            log.warn(
                "presence hook <{}> failed; the №18 channel is unaffected and the registration " +
                    "converges via TTL/№37-reconnect (FR-002): {}",
                what,
                failure.message,
            )
        }
    }

    private companion object {
        /** realtime-channel.md §1: the reconnect hint of the stream's opening frame. */
        const val RECONNECT_HINT_MILLIS = 3_000L

        /** research.md 004 §1: no server-side async timeout — heartbeats keep the stream alive. */
        const val NEVER_TIME_OUT = 0L

        /** nginx/ingress switch: deliver frames immediately, without buffering (research.md 004 §1). */
        const val PROXY_BUFFERING_HEADER = "X-Accel-Buffering"
        const val PROXY_BUFFERING_OFF = "no"

        /** AuthJwtDecoder: the 002 session claim every verified access token carries. */
        const val SESSION_ID_CLAIM = "sid"

        /** presence-events.md §1: the №18 opening `event:` value and its payload field (007). */
        const val CONNECTED_EVENT = "connected"
        const val CONNECTION_ID_FIELD = "connectionId"

        /**
         * The opening frames are pre-rendered WHATWG text (`retry: 3000`,
         * `event: connected` + `data: {…}`, exactly as realtime-channel.md
         * §2 / presence-events.md §1 spell them) and must reach the
         * wire as UTF-8: `text/plain` alone would fall back to
         * StringHttpMessageConverter's ISO-8859-1 default.
         */
        val FRAME_MEDIA_TYPE: MediaType = MediaType("text", "plain", StandardCharsets.UTF_8)
    }
}
