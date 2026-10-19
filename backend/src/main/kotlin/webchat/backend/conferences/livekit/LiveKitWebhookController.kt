package webchat.backend.conferences.livekit

import com.google.protobuf.InvalidProtocolBufferException
import io.livekit.server.WebhookReceiver
import livekit.LivekitWebhook
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import webchat.backend.conferences.ConferenceMetrics
import webchat.backend.conferences.ConferenceMetrics.WebhookEvent
import webchat.backend.conferences.ConferenceMetrics.WebhookResult
import webchat.backend.conferences.domain.service.ConferenceLifecycleService
import webchat.backend.conferences.domain.service.ConferenceWebhookEvent
import webchat.backend.config.ConferencesProperties
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * T053 (009, US5) — the LiveKit webhook intake, livekit-integration.md
 * §5: `POST /api/internal/conferences/livekit/webhook` is the media
 * plane's ONLY way into the backend. The route stands `permitAll` in
 * SecurityConfig (a media plane carries no Bearer) behind its own
 * per-IP bucket `rl:ip:conferences-livekit-webhook` 60/1m — both open
 * together with THIS controller, so the public path never precedes
 * its handler (the T047 discipline).
 *
 *  * **Raw-body discipline**: the body arrives as [ByteArray] and is
 *    verified as the exact received bytes — the webhook JWT carries a
 *    sha256 claim over the body, so any string round-trip before the
 *    check would risk a signature over re-encoded bytes. The UTF-8
 *    decode handed to [WebhookReceiver.receive] is a lossless
 *    round-trip for the plane's UTF-8 payloads.
 *  * **Authenticity**: the receiver verifies the HMAC signature and
 *    the issuer (`iss` = the LiveKit API key) — every rejection mode
 *    (missing/blank header, bad signature, sha mismatch) answers the
 *    single uniform 401; a valid signature answers 200 ALWAYS, even
 *    when processing fails, so valid events are never lost to a
 *    retry-suppressing error status (the sweeper of T056 is the
 *    delivery-loss safety net, research §7).
 *  * **Dedup**: LiveKit retries deliveries, and a K8s Service may
 *    hand the retry to another replica — the `event.id` marker is
 *    therefore a short-TTL Redis SETNX, not per-process memory
 *    (constitution II). The window generously covers the retry
 *    horizon; correctness NEVER rests on it — the state transitions
 *    of T054/T055 are conditional in the DB and idempotent by
 *    themselves (§5 «Идемпотентность»). Redis unavailability fails
 *    OPEN, exactly like the rate-limit buckets.
 *  * **Privacy** (§5 «Приватность»): the payload is NEVER logged —
 *    not even at debug; the only log surface is the event TYPE of an
 *    accepted/duplicate delivery, which carries no PII.
 *
 * The event PROCESSING (status transitions, SSE, join/left marks,
 * `processed`/`ignored` metrics) is the T054 `ConferenceLifecycleService`
 * — this class owns the intake boundary only.
 */
@RestController
class LiveKitWebhookController(
    properties: ConferencesProperties,
    private val metrics: ConferenceMetrics,
    private val redisTemplate: StringRedisTemplate,
    private val lifecycle: ConferenceLifecycleService,
) {
    private val webhookReceiver = WebhookReceiver(properties.livekit.apiKey, properties.livekit.apiSecret)

    // Every return IS a delivery-leg verdict of §5 (401 / parse no-op /
    // identity-less no-op / duplicate / accepted), the RateLimitFilter
    // convention; the generic catch folds the SDK's
    // JWTVerificationException — RUNTIME-scoped in the livekit-server
    // POM, unreferenceable here — and every other verification-adjacent
    // failure into the uniform 401
    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    @PostMapping(WEBHOOK_PATH)
    fun receive(
        @RequestBody rawBody: ByteArray,
        @RequestHeader(HttpHeaders.AUTHORIZATION) authHeader: String?,
    ): ResponseEntity<Unit> {
        val event =
            try {
                webhookReceiver.receive(String(rawBody, StandardCharsets.UTF_8), authHeader)
            } catch (rejected: InvalidProtocolBufferException) {
                // a VALID signature over an unparseable body — processing
                // cannot run, but the delivery itself was authentic: 200
                // keeps the plane from pointlessly retrying it
                log.warn("a validly signed webhook body did not parse, answered 200: {}", rejected.toString())
                return OK
            } catch (rejected: RuntimeException) {
                // missing/blank Authorization, bad signature, sha mismatch
                // — the uniform rejection of livekit-integration.md §5
                // (exception text only: no payload ever reaches the logs)
                log.debug("rejected a webhook delivery with an invalid signature: {}", rejected.toString())
                return UNAUTHORIZED
            }

        // a signature-verified event with no usable identity: nothing to
        // dedup, nothing to process — the 200-always rule again
        if (event.id.isBlank()) {
            log.debug("accepted a webhook delivery with no event identity; answered 200 without processing")
            return OK
        }

        if (!markSeen(event.id)) {
            metrics.countWebhookProcessed(WebhookEvent.of(event.event), WebhookResult.DUPLICATE)
            log.debug("skipped a duplicate webhook delivery of event type <{}>", event.event)
            return OK
        }

        // intake complete; the T054 lifecycle service consumes the
        // event here — and a processing failure is STILL the 200 of
        // the 200-always rule (§5 «Доступность»: valid events are
        // never lost to a retry-suppressing error status); the
        // `processed`/`ignored` metric samples close inside the
        // service, `duplicate` above stays this boundary's own
        // (`TooGenericExceptionCaught` suppressed: the failure text
        // only — no payload — reaches the log, constitution V)
        log.debug("accepted a webhook delivery of event type <{}>", event.event)
        try {
            lifecycle.onWebhook(domainEventOf(event))
        } catch (failure: Exception) {
            log.warn(
                "processing of a webhook delivery of event type <{}> failed; answered 200 by the availability rule " +
                    "(the sweeper of T056 compensates a lost state effect): {}",
                event.event,
                failure.toString(),
            )
        }
        return OK
    }

    /**
     * The T054 hand-off mapping: the signature-verified protobuf folds
     * onto the DOMAIN event set — this adapter is the ONLY place the
     * SDK type is readable (DIP); absent room/participant fields ride
     * as empty strings and resolve to the service's tagged no-ops,
     * and the identity/room values cross verbatim without ever
     * reaching a log (§5 «Приватность»).
     */
    private fun domainEventOf(event: LivekitWebhook.WebhookEvent): ConferenceWebhookEvent =
        when (event.event) {
            EVENT_ROOM_STARTED -> ConferenceWebhookEvent.RoomStarted(event.room.name)
            EVENT_PARTICIPANT_JOINED ->
                ConferenceWebhookEvent.ParticipantJoined(event.room.name, event.participant.identity)
            EVENT_PARTICIPANT_LEFT ->
                ConferenceWebhookEvent.ParticipantLeft(event.room.name, event.participant.identity)
            EVENT_ROOM_FINISHED -> ConferenceWebhookEvent.RoomFinished(event.room.name)
            else -> ConferenceWebhookEvent.Unhandled(event.event)
        }

    /**
     * The `event.id` dedup marker — `SET NX EX` under [DEDUP_KEY_PREFIX]:
     * `false` means the id was already seen inside [DEDUP_WINDOW].
     * Redis unavailability fails OPEN (the DB-level conditional
     * transitions remain the correctness backstop, §5).
     */
    private fun markSeen(eventId: String): Boolean =
        runCatching {
            redisTemplate.opsForValue().setIfAbsent("$DEDUP_KEY_PREFIX$eventId", DEDUP_MARKER, DEDUP_WINDOW)
        }.onFailure {
            log.warn("the webhook dedup store is unavailable, failing open: {}", it.toString())
        }.getOrDefault(true) != false

    companion object {
        private val log = LoggerFactory.getLogger(LiveKitWebhookController::class.java)

        /** livekit-integration.md §5 — the webhook URL of the media-plane config (deploy/local, k8s). */
        const val WEBHOOK_PATH = "/api/internal/conferences/livekit/webhook"

        /** The Redis family of the `event.id` dedup markers — short like the `rl:` bucket keys, same store. */
        const val DEDUP_KEY_PREFIX = "lkw:dedup:"

        const val DEDUP_MARKER = "1"

        /**
         * The dedup horizon: generously beyond the LiveKit delivery-retry
         * window (§5 «в рамках окна ретраев LiveKit») while bounded, so
         * the markers expire and the Redis set stays constant-size.
         */
        val DEDUP_WINDOW: Duration = Duration.ofMinutes(5)

        /** livekit-integration.md §5: the handled wire event labels (the T054 mapping vocabulary). */
        const val EVENT_ROOM_STARTED = "room_started"

        const val EVENT_PARTICIPANT_JOINED = "participant_joined"

        const val EVENT_PARTICIPANT_LEFT = "participant_left"

        const val EVENT_ROOM_FINISHED = "room_finished"

        private val OK = ResponseEntity<Unit>(HttpStatus.OK)

        private val UNAUTHORIZED = ResponseEntity<Unit>(HttpStatus.UNAUTHORIZED)
    }
}
