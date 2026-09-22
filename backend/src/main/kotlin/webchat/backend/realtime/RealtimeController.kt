package webchat.backend.realtime

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
 */
@RestController
@RequestMapping("/api/v1/users/me")
class RealtimeController(
    private val connectionRegistry: SseConnectionRegistry,
) {
    /**
     * Contract №18: the per-user SSE stream — see the class KDoc. The
     * emitter's lifecycle callbacks keep the registry exact: completion,
     * error and timeout all funnel into the unregister of this session.
     */
    @GetMapping("/events", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun streamUserEvents(
        @AuthenticationPrincipal accessToken: Jwt,
    ): ResponseEntity<SseEmitter> {
        val userId = UUID.fromString(accessToken.subject)
        val emitter = SseEmitter(NEVER_TIME_OUT)
        emitter.onCompletion { connectionRegistry.unregister(userId, emitter) }
        emitter.onTimeout { emitter.complete() }
        emitter.onError { connectionRegistry.unregister(userId, emitter) }
        // The Set overload of ResponseBodyEmitter writes items VERBATIM —
        // the Object overloads of SseEmitter would wrap the text as `data:`
        // lines (realtime-channel.md §2 spells the retry field, not data).
        emitter.send(
            setOf(ResponseBodyEmitter.DataWithMediaType("retry: $RECONNECT_HINT_MILLIS\n\n", FRAME_MEDIA_TYPE)),
        )
        connectionRegistry.register(userId, emitter)
        return ResponseEntity
            .ok()
            .header(PROXY_BUFFERING_HEADER, PROXY_BUFFERING_OFF)
            .body(emitter)
    }

    private companion object {
        /** realtime-channel.md §1: the reconnect hint of the stream's opening frame. */
        const val RECONNECT_HINT_MILLIS = 3_000L

        /** research.md 004 §1: no server-side async timeout — heartbeats keep the stream alive. */
        const val NEVER_TIME_OUT = 0L

        /** nginx/ingress switch: deliver frames immediately, without buffering (research.md 004 §1). */
        const val PROXY_BUFFERING_HEADER = "X-Accel-Buffering"
        const val PROXY_BUFFERING_OFF = "no"

        /**
         * The opening frame is pre-rendered WHATWG text (`retry: 3000`,
         * exactly as realtime-channel.md §2 spells it) and must reach the
         * wire as UTF-8: `text/plain` alone would fall back to
         * StringHttpMessageConverter's ISO-8859-1 default.
         */
        val FRAME_MEDIA_TYPE: MediaType = MediaType("text", "plain", StandardCharsets.UTF_8)
    }
}
