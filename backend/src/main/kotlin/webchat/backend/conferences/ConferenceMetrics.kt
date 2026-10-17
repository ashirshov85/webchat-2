package webchat.backend.conferences

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import webchat.backend.conferences.domain.model.ConferenceEndReason
import java.util.concurrent.atomic.AtomicLong

/**
 * T014 — the FR-018/SC-009 observability vocabulary of research.md
 * §13, on the shared Micrometer stack of 001 — no new dependencies
 * (the `GroupMetrics`/`PresenceMetrics` convention):
 *
 *  * gauge `webchat_conference_active` — the live (non-ended)
 *    conference count of FR-018 «число активных конференций»: a
 *    `created` room waiting for its first participant and an
 *    `active` call alike. Sampled at observation time by an
 *    index-only `count(*)` over `ix_conferences_status` (T014:
 *    «gauge по ix_conferences_status») — the gauge re-reads the
 *    index on every exposition, never a cached snapshot;
 *  * gauge `webchat_conference_participants_connected` — the
 *    connected-participants count of FR-018 «…и участников».
 *    Media truth lives in LiveKit and the DB mirrors none of it
 *    (research §4/§7: two tabs of one member are TWO connections,
 *    ONE principal row), so this is the SNAPSHOT refreshed by the
 *    sweeper's `ListRooms` scan (T056, research §13 «снимок
 *    sweeper'ом») via [updateParticipantsConnected];
 *  * counter `webchat_conference_started_total` — the winning
 *    `created→active` of the FIRST `participant_joined` (T054,
 *    US5-1: a conference starts at the actual first join, not at
 *    №44). Untagged: research §13 annotates the started/ended pair
 *    with `{end_reason}` — a start has no end reason, the tag
 *    belongs to the ended counter alone;
 *  * counter `webchat_conference_ended_total{end_reason}` — the
 *    winning finalize per path of the five V18 reasons (T055:
 *    `last_left|moderator|max_duration|room_lost|chat_deleted`);
 *  * counter `webchat_conference_joins_total{kind=member|guest}` —
 *    actual joins by principals (T018/T046/T054);
 *  * counter `webchat_conference_joins_denied_total{reason=
 *    membership|quota|invite|rate_limit}` — the join refusals of
 *    FR-018: `membership` — the №47/№55 gate (403, SC-002), `quota`
 *    — the `conference_full` application of `maxParticipants`
 *    (FR-015, «применение квот»), `invite` — guest-link denials
 *    (invalid/expired/revoked nonce, SC-003), `rate_limit` — the
 *    429 of the public per-IP buckets (T047, SC-004);
 *  * counter `webchat_conference_tokens_minted_total{kind}` —
 *    JoinTicket mints (T018 member / T046 guest);
 *  * counter `webchat_conference_moderation_actions_total{action}`
 *    — №48 `end`, №49 `set_restrictions`, №50 `set_role`, №51
 *    `kick` (T036–T039);
 *  * counter `webchat_conference_webhooks_processed_total{event,
 *    result}` — the LiveKit webhook intake of T053/T054: `event` ∈
 *    the handled set (`room_started`, `participant_joined`,
 *    `participant_left`, `room_finished`) plus `other` for the
 *    200/no-op rest, `result` ∈ `processed` (a handled event),
 *    `duplicate` (the `event.id` dedup), `ignored` (no-op);
 *  * timer `webchat_conference_livekit_api_seconds{method}` — the
 *    admin Twirp calls of livekit-integration.md §4 by their wire
 *    method names (the T012 adapter), the media-plane health signal
 *    behind the `503 conference_media_unavailable` edge.
 *
 * The research §13 gauge names (`conferences_active`,
 * `participants_connected`) render under the uniform
 * `webchat_conference_*` family of T014/T067/FR-018 without
 * repeating the domain word — the `webchat_group_size` convention.
 * The names carry the Prometheus suffixes verbatim (`_total` is
 * idempotent for counters, `_seconds` is the timer base unit), so
 * `/actuator/prometheus` renders exactly the series T067 (SC-009)
 * and quickstart §4 assert.
 *
 * Constitution V: tag values carry the operation/reason
 * vocabularies only — never chat, user or connection identifiers;
 * identities and webhook payloads stay out of the tag space.
 */
@Component
class ConferenceMetrics(
    private val meterRegistry: MeterRegistry,
    private val jdbcTemplate: JdbcTemplate,
) {
    /**
     * The gauge registration (the `PresenceMetrics` pattern):
     * Micrometer samples the index-only count when the exposition is
     * read; the strong field reference keeps the weakly-held gauge
     * alive for the bean's lifetime.
     */
    @Suppress("UnusedPrivateProperty") // the ONLY strong reference keeping the weakly-held gauge alive
    private val activeConferencesGauge: Gauge =
        Gauge
            .builder(ACTIVE) { countActiveConferences().toDouble() }
            .description(
                "Currently live conferences (created + active), sampled via ix_conferences_status (FR-018/SC-009)",
            ).register(meterRegistry)

    private val participantsConnected = AtomicLong()

    /**
     * Same weak-gauge discipline as [activeConferencesGauge] for the
     * sweeper snapshot: the value is the LAST [updateParticipantsConnected]
     * refresh, zero until the sweeper (T056) has run.
     */
    @Suppress("UnusedPrivateProperty") // the ONLY strong reference keeping the weakly-held gauge alive
    private val participantsConnectedGauge: Gauge =
        Gauge
            .builder(PARTICIPANTS_CONNECTED) { participantsConnected.get().toDouble() }
            .description(
                "Currently connected participants: the sweeper's ListRooms snapshot (FR-018/SC-009)",
            ).register(meterRegistry)

    /**
     * The sweeper refresh (T056, research §13 «снимок sweeper'ом»):
     * [connected] is the room-by-room sum of live connections of the
     * `ListRooms` scan — the media-plane truth the DB does not mirror.
     */
    fun updateParticipantsConnected(connected: Int) {
        participantsConnected.set(connected.toLong())
    }

    /**
     * Counts one started conference — the WINNING `created→active`
     * conditional UPDATE of the first `participant_joined` (T054,
     * US5-1); duplicates and out-of-order webhooks never reach here.
     */
    fun countConferenceStarted() {
        Counter
            .builder(STARTED_TOTAL)
            .description("Conferences started: the winning created→active of the first participant_joined (FR-018)")
            .register(meterRegistry)
            .increment()
    }

    /**
     * Counts one finalized conference of [reason] — the WINNING
     * finalize of every end path (T055); a duplicate `room_finished`
     * no-op never increments (invariant 5).
     */
    fun countConferenceEnded(reason: ConferenceEndReason) {
        meterRegistry
            .counter(ENDED_TOTAL, TAG_END_REASON, reason.name.lowercase())
            .increment()
    }

    /**
     * Counts one actual join of [kind] — member (№47) or guest (№55)
     * legs alike (FR-018 «события жизненного цикла»).
     */
    fun countJoin(kind: JoinKind) {
        meterRegistry
            .counter(JOINS_TOTAL, TAG_KIND, kind.value)
            .increment()
    }

    /**
     * Counts one refused join of [reason] — the FR-018 denial
     * surface: membership gate, quota (`conference_full`), guest
     * invite and public rate limiting (SC-002/SC-003/SC-004).
     */
    fun countJoinDenied(reason: JoinDenialReason) {
        meterRegistry
            .counter(JOINS_DENIED_TOTAL, TAG_REASON, reason.value)
            .increment()
    }

    /**
     * Counts one minted JoinTicket of [kind] — the №47/№55 token
     * issuances (T018/T046).
     */
    fun countTokensMinted(kind: JoinKind) {
        meterRegistry
            .counter(TOKENS_MINTED_TOTAL, TAG_KIND, kind.value)
            .increment()
    }

    /**
     * Counts one moderator action of [action] — №48–№51 (T036–T039).
     */
    fun countModerationAction(action: ModerationAction) {
        meterRegistry
            .counter(MODERATION_ACTIONS_TOTAL, TAG_ACTION, action.value)
            .increment()
    }

    /**
     * Counts one processed LiveKit webhook of [event] with the
     * [result] outcome — the intake behind T053/T054, including the
     * `event.id` dedup (`duplicate`) and the 200/no-op rest
     * (`ignored`); a rejected signature never reaches the handler.
     */
    fun countWebhookProcessed(
        event: WebhookEvent,
        result: WebhookResult,
    ) {
        meterRegistry
            .counter(WEBHOOKS_PROCESSED_TOTAL, TAG_EVENT, event.value, TAG_RESULT, result.value)
            .increment()
    }

    /**
     * Starts a `webchat_conference_livekit_api_seconds{method}`
     * sample; the caller stops it with [LiveKitApiSample.stop] after
     * the Twirp call of the T012 adapter — the media-plane health
     * signal behind the `503 conference_media_unavailable` edge.
     */
    fun startLiveKitApi(method: LiveKitMethod): LiveKitApiSample = LiveKitApiSample(method, Timer.start(meterRegistry))

    /** The live-conference count: an index-only scan, `created` + `active` alike (FR-018). */
    private fun countActiveConferences(): Long = jdbcTemplate.queryForObject(COUNT_ACTIVE_SQL, Long::class.java) ?: 0L

    /** A running `webchat_conference_livekit_api_seconds` measurement stopped exactly once. */
    inner class LiveKitApiSample(
        private val method: LiveKitMethod,
        private val sample: Timer.Sample,
    ) {
        fun stop() {
            sample.stop(liveKitApiTimer())
        }

        private fun liveKitApiTimer(): Timer =
            Timer
                .builder(LIVEKIT_API_SECONDS)
                .tag(TAG_METHOD, method.value)
                .description("Latency of the LiveKit admin Twirp calls by method (livekit-integration.md §4)")
                .register(meterRegistry)
    }

    /** The principal kind of a join/mint leg (research §13 `{kind=member|guest}`). */
    enum class JoinKind(
        val value: String,
    ) {
        MEMBER("member"),
        GUEST("guest"),
    }

    /** The join-refusal vocabulary of research §13 (FR-018, SC-002/SC-003/SC-004). */
    enum class JoinDenialReason(
        val value: String,
    ) {
        MEMBERSHIP("membership"),
        QUOTA("quota"),
        INVITE("invite"),
        RATE_LIMIT("rate_limit"),
    }

    /** The moderation operations №48–№51 of api-contract.md §2 (T036–T039). */
    enum class ModerationAction(
        val value: String,
    ) {
        END("end"),
        SET_RESTRICTIONS("set_restrictions"),
        SET_ROLE("set_role"),
        KICK("kick"),
    }

    /** The handled LiveKit webhook events plus the 200/no-op rest (T053/T054). */
    enum class WebhookEvent(
        val value: String,
    ) {
        ROOM_STARTED("room_started"),
        PARTICIPANT_JOINED("participant_joined"),
        PARTICIPANT_LEFT("participant_left"),
        ROOM_FINISHED("room_finished"),
        OTHER("other"),
    }

    /** The outcome of one webhook intake (T053/T054). */
    enum class WebhookResult(
        val value: String,
    ) {
        PROCESSED("processed"),
        DUPLICATE("duplicate"),
        IGNORED("ignored"),
    }

    /** The admin Twirp methods of livekit-integration.md §4 by their wire names (T012). */
    enum class LiveKitMethod(
        val value: String,
    ) {
        CREATE_ROOM("CreateRoom"),
        DELETE_ROOM("DeleteRoom"),
        LIST_ROOMS("ListRooms"),
        LIST_PARTICIPANTS("ListParticipants"),
        REMOVE_PARTICIPANT("RemoveParticipant"),
        UPDATE_PARTICIPANT("UpdateParticipant"),
        MUTE_PUBLISHED_TRACK("MutePublishedTrack"),
        SEND_DATA("SendData"),
    }

    private companion object {
        // research.md §13 observability contract names (FR-018, SC-009)
        const val ACTIVE = "webchat_conference_active"

        const val PARTICIPANTS_CONNECTED = "webchat_conference_participants_connected"

        const val STARTED_TOTAL = "webchat_conference_started_total"

        const val ENDED_TOTAL = "webchat_conference_ended_total"

        const val JOINS_TOTAL = "webchat_conference_joins_total"

        const val JOINS_DENIED_TOTAL = "webchat_conference_joins_denied_total"

        const val TOKENS_MINTED_TOTAL = "webchat_conference_tokens_minted_total"

        const val MODERATION_ACTIONS_TOTAL = "webchat_conference_moderation_actions_total"

        const val WEBHOOKS_PROCESSED_TOTAL = "webchat_conference_webhooks_processed_total"

        const val LIVEKIT_API_SECONDS = "webchat_conference_livekit_api_seconds"

        const val TAG_END_REASON = "end_reason"

        const val TAG_KIND = "kind"

        const val TAG_REASON = "reason"

        const val TAG_ACTION = "action"

        const val TAG_EVENT = "event"

        const val TAG_RESULT = "result"

        const val TAG_METHOD = "method"

        /** Index-only range scan over ix_conferences_status: the live statuses, never the ended bulk. */
        const val COUNT_ACTIVE_SQL = "SELECT count(*) FROM conferences WHERE status IN ('created', 'active')"
    }
}
