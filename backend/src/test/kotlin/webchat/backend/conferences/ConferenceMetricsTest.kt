package webchat.backend.conferences

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import org.springframework.jdbc.core.JdbcTemplate
import webchat.backend.conferences.domain.model.ConferenceEndReason

/**
 * T014 — unit-level acceptance of the FR-018/SC-009 observability
 * vocabulary that `ConferenceMetrics` must expose (research.md §13),
 * on the shared Micrometer stack — no new dependencies:
 *
 *  * gauge `webchat_conference_active` — the live (non-ended:
 *    `created` + `active`) conference count, sampled at observation
 *    time by an index-only scan over `ix_conferences_status`
 *    (FR-018 «число активных конференций»);
 *  * gauge `webchat_conference_participants_connected` — the
 *    connected-participants snapshot refreshed by the sweeper (T056,
 *    research §13 «снимок sweeper'ом»: the media truth lives in
 *    LiveKit, the DB mirrors no connections — research §4/§7);
 *  * counter `webchat_conference_started_total` — the winning
 *    `created→active` of the first `participant_joined` (T054,
 *    US5-1; research §13 annotates the pair with `{end_reason}` — a
 *    START has no end reason, the tag belongs to the ended counter
 *    below);
 *  * counter `webchat_conference_ended_total{end_reason}` — the
 *    winning finalize per path (T055, the five V18 reasons);
 *  * counter `webchat_conference_joins_total{kind=member|guest}` —
 *    joins of members and guests (T018/T046/T054);
 *  * counter `webchat_conference_joins_denied_total{reason=
 *    membership|quota|invite|rate_limit}` — membership refusals,
 *    quota application (`conference_full`, FR-015) and guest-entry
 *    denials incl. rate limiting (FR-018, SC-003/SC-004);
 *  * counter `webchat_conference_tokens_minted_total{kind}` —
 *    JoinTicket mints (T018/T046);
 *  * counter `webchat_conference_moderation_actions_total{action}`
 *    — №48–№51 (T036–T039);
 *  * counter `webchat_conference_webhooks_processed_total{event,
 *    result}` — webhook intake incl. the `event.id` dedup (T053/T054);
 *  * timer `webchat_conference_livekit_api_seconds{method}` — the
 *    admin Twirp calls of livekit-integration.md §4 (T012 adapter).
 *
 * The names carry the Prometheus suffixes verbatim (`_total` is
 * idempotent for counters, `_seconds` is the timer base unit), so
 * `/actuator/prometheus` renders exactly the series T067 (SC-009)
 * asserts — that end-to-end rendering is out of scope here, as are
 * the call sites (create/join denials — T017/T018, moderation —
 * T036–T039, guest denials — T046/T047, webhooks/lifecycle/snapshot
 * — T053–T056), following the GroupMetricsTest precedent.
 *
 * Constitution V: tag values carry the operation/reason vocabularies
 * only — never chat, user or connection identifiers.
 */
class ConferenceMetricsTest {
    private val meterRegistry = SimpleMeterRegistry()

    private val jdbcTemplate = Mockito.mock(JdbcTemplate::class.java)

    private val metrics = ConferenceMetrics(meterRegistry, jdbcTemplate)

    // --- webchat_conference_active ---

    @Test
    fun `active gauge registers eagerly and samples the count query at observation time`() {
        stubActiveConferences(7)

        val gauge = meterRegistry.find(ACTIVE).gauge()

        assertThat(gauge).isNotNull
        assertThat(gauge!!.value()).isEqualTo(7.0)

        // scrape-time sampling: the lambda re-reads the index on every
        // exposition, never caches a stale snapshot
        stubActiveConferences(9)
        assertThat(gauge.value()).isEqualTo(9.0)
    }

    @Test
    fun `active gauge counts the live statuses over ix_conferences_status`() {
        stubActiveConferences(0)

        assertThat(meterRegistry.find(ACTIVE).gauge()!!.value()).isEqualTo(0.0)

        val sql = ArgumentCaptor.forClass(String::class.java)
        Mockito
            .verify(jdbcTemplate, Mockito.atLeastOnce())
            .queryForObject(sql.capture(), Mockito.eq(Long::class.java))
        // the index range scan shapes the gauge (T014: «gauge по
        // ix_conferences_status») — `created` rooms waiting for the
        // first join count as live conferences too (FR-018)
        assertThat(sql.value).contains("FROM conferences").contains("status IN ('created', 'active')")
    }

    // --- webchat_conference_participants_connected ---

    @Test
    fun `participants connected gauge registers eagerly at zero and reflects sweeper snapshots`() {
        val gauge = meterRegistry.find(PARTICIPANTS_CONNECTED).gauge()

        assertThat(gauge).isNotNull
        assertThat(gauge!!.value()).isEqualTo(0.0)

        metrics.updateParticipantsConnected(12)

        assertThat(gauge.value()).isEqualTo(12.0)

        metrics.updateParticipantsConnected(3)

        assertThat(gauge.value()).isEqualTo(3.0)
    }

    // --- webchat_conference_started_total ---

    @Test
    fun `started counter registers on first start and grows monotonically`() {
        assertThat(meterRegistry.find(STARTED_TOTAL).counters()).isEmpty()

        metrics.countConferenceStarted()

        assertThat(meterRegistry.find(STARTED_TOTAL).counter()).isNotNull
        assertThat(meterRegistry.find(STARTED_TOTAL).counter()!!.count()).isEqualTo(1.0)

        metrics.countConferenceStarted()
        metrics.countConferenceStarted()

        assertThat(meterRegistry.find(STARTED_TOTAL).counter()!!.count()).isEqualTo(3.0)
    }

    // --- webchat_conference_ended_total ---

    @Test
    fun `ended counter splits finalize paths per end_reason tag`() {
        metrics.countConferenceEnded(ConferenceEndReason.LAST_LEFT)
        metrics.countConferenceEnded(ConferenceEndReason.MODERATOR)
        metrics.countConferenceEnded(ConferenceEndReason.LAST_LEFT)

        assertThat(
            meterRegistry
                .find(ENDED_TOTAL)
                .tag(TAG_END_REASON, "last_left")
                .counter()!!
                .count(),
        ).isEqualTo(2.0)
        assertThat(
            meterRegistry
                .find(ENDED_TOTAL)
                .tag(TAG_END_REASON, "moderator")
                .counter()!!
                .count(),
        ).isEqualTo(1.0)
        assertThat(meterRegistry.find(ENDED_TOTAL).counters().sumOf { it.count() }).isEqualTo(3.0)
    }

    @Test
    fun `ended counter covers the five V18 finalize reasons`() {
        ConferenceEndReason.entries.forEach(metrics::countConferenceEnded)

        assertThat(meterRegistry.find(ENDED_TOTAL).counters()).hasSize(5)
        assertThat(meterRegistry.find(ENDED_TOTAL).counters().sumOf { it.count() }).isEqualTo(5.0)
    }

    // --- webchat_conference_joins_total ---

    @Test
    fun `joins counter splits per kind tag`() {
        metrics.countJoin(ConferenceMetrics.JoinKind.MEMBER)
        metrics.countJoin(ConferenceMetrics.JoinKind.GUEST)
        metrics.countJoin(ConferenceMetrics.JoinKind.MEMBER)

        assertThat(
            meterRegistry
                .find(JOINS_TOTAL)
                .tag(TAG_KIND, "member")
                .counter()!!
                .count(),
        ).isEqualTo(2.0)
        assertThat(
            meterRegistry
                .find(JOINS_TOTAL)
                .tag(TAG_KIND, "guest")
                .counter()!!
                .count(),
        ).isEqualTo(1.0)
    }

    // --- webchat_conference_joins_denied_total ---

    @Test
    fun `joins denied counter splits per reason tag and covers the research vocabulary`() {
        assertThat(
            enumValues<ConferenceMetrics.JoinDenialReason>().map { it.value },
        ).containsExactly("membership", "quota", "invite", "rate_limit")

        enumValues<ConferenceMetrics.JoinDenialReason>().forEach(metrics::countJoinDenied)

        assertThat(
            meterRegistry
                .find(JOINS_DENIED_TOTAL)
                .tag(TAG_REASON, "membership")
                .counter()!!
                .count(),
        ).isEqualTo(1.0)
        assertThat(meterRegistry.find(JOINS_DENIED_TOTAL).counters()).hasSize(4)
    }

    // --- webchat_conference_tokens_minted_total ---

    @Test
    fun `tokens minted counter splits per kind tag`() {
        metrics.countTokensMinted(ConferenceMetrics.JoinKind.GUEST)
        metrics.countTokensMinted(ConferenceMetrics.JoinKind.GUEST)

        assertThat(
            meterRegistry
                .find(TOKENS_MINTED_TOTAL)
                .tag(TAG_KIND, "guest")
                .counter()!!
                .count(),
        ).isEqualTo(2.0)
        assertThat(meterRegistry.find(TOKENS_MINTED_TOTAL).counters()).hasSize(1)
    }

    // --- webchat_conference_moderation_actions_total ---

    @Test
    fun `moderation counter covers the 48-51 action vocabulary`() {
        assertThat(
            enumValues<ConferenceMetrics.ModerationAction>().map { it.value },
        ).containsExactly("end", "set_restrictions", "set_role", "kick")

        enumValues<ConferenceMetrics.ModerationAction>().forEach(metrics::countModerationAction)

        assertThat(meterRegistry.find(MODERATION_ACTIONS_TOTAL).counters()).hasSize(4)
        assertThat(
            meterRegistry
                .find(MODERATION_ACTIONS_TOTAL)
                .tag(TAG_ACTION, "set_restrictions")
                .counter()!!
                .count(),
        ).isEqualTo(1.0)
    }

    // --- webchat_conference_webhooks_processed_total ---

    @Test
    fun `webhooks counter splits per event and result tags`() {
        metrics.countWebhookProcessed(
            ConferenceMetrics.WebhookEvent.PARTICIPANT_JOINED,
            ConferenceMetrics.WebhookResult.PROCESSED,
        )
        metrics.countWebhookProcessed(
            ConferenceMetrics.WebhookEvent.PARTICIPANT_JOINED,
            ConferenceMetrics.WebhookResult.DUPLICATE,
        )
        metrics.countWebhookProcessed(
            ConferenceMetrics.WebhookEvent.ROOM_STARTED,
            ConferenceMetrics.WebhookResult.IGNORED,
        )

        assertThat(
            meterRegistry
                .find(WEBHOOKS_PROCESSED_TOTAL)
                .tag(TAG_EVENT, "participant_joined")
                .tag(TAG_RESULT, "processed")
                .counter()!!
                .count(),
        ).isEqualTo(1.0)
        assertThat(
            meterRegistry
                .find(WEBHOOKS_PROCESSED_TOTAL)
                .tag(TAG_EVENT, "participant_joined")
                .tag(TAG_RESULT, "duplicate")
                .counter()!!
                .count(),
        ).isEqualTo(1.0)
        assertThat(
            meterRegistry
                .find(WEBHOOKS_PROCESSED_TOTAL)
                .tag(TAG_EVENT, "room_started")
                .tag(TAG_RESULT, "ignored")
                .counter()!!
                .count(),
        ).isEqualTo(1.0)
    }

    @Test
    fun `webhook vocabulary covers the handled events and outcomes`() {
        assertThat(
            enumValues<ConferenceMetrics.WebhookEvent>().map { it.value },
        ).containsExactly(
            "room_started",
            "participant_joined",
            "participant_left",
            "room_finished",
            "other",
        )
        assertThat(
            enumValues<ConferenceMetrics.WebhookResult>().map { it.value },
        ).containsExactly("processed", "duplicate", "ignored")
    }

    // --- webchat_conference_livekit_api_seconds ---

    @Test
    fun `livekit api timer registers on first stopped sample with the method tag`() {
        assertThat(meterRegistry.find(LIVEKIT_API_SECONDS).timers()).isEmpty()

        metrics.startLiveKitApi(ConferenceMetrics.LiveKitMethod.CREATE_ROOM).stop()

        val timer =
            meterRegistry
                .find(LIVEKIT_API_SECONDS)
                .tag(TAG_METHOD, "CreateRoom")
                .timer()
        assertThat(timer).isNotNull
        assertThat(timer!!.count()).isEqualTo(1L)

        metrics.startLiveKitApi(ConferenceMetrics.LiveKitMethod.CREATE_ROOM).stop()

        assertThat(timer.count()).isEqualTo(2L)
    }

    @Test
    fun `livekit api method vocabulary covers the admin port of livekit-integration 4`() {
        assertThat(
            enumValues<ConferenceMetrics.LiveKitMethod>().map { it.value },
        ).containsExactly(
            "CreateRoom",
            "DeleteRoom",
            "ListRooms",
            "ListParticipants",
            "RemoveParticipant",
            "UpdateParticipant",
            "MutePublishedTrack",
            "SendData",
        )
    }

    private fun stubActiveConferences(count: Long) {
        Mockito
            .`when`(jdbcTemplate.queryForObject(Mockito.anyString(), Mockito.eq(Long::class.java)))
            .thenReturn(count)
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
    }
}
