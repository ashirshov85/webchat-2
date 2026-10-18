package webchat.backend.conferences.domain.service

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.SimpleTransactionStatus
import webchat.backend.auth.domain.port.Clock
import webchat.backend.conferences.ConferenceMetrics
import webchat.backend.conferences.api.dto.ConferenceMode
import webchat.backend.conferences.domain.model.Conference
import webchat.backend.conferences.domain.model.ConferenceIdentity
import webchat.backend.conferences.domain.port.ChatMembershipChecker
import webchat.backend.conferences.domain.port.ConferenceEventPublisher
import webchat.backend.conferences.domain.port.ConferenceModeReader
import webchat.backend.conferences.domain.port.ConferenceRepository
import webchat.backend.conferences.domain.port.ConferenceStartedEvent
import webchat.backend.conferences.domain.port.ParticipantRepository
import webchat.backend.conferences.domain.service.ConferenceWebhookEvent.ParticipantJoined
import webchat.backend.conferences.domain.service.ConferenceWebhookEvent.ParticipantLeft
import webchat.backend.conferences.domain.service.ConferenceWebhookEvent.RoomFinished
import webchat.backend.conferences.domain.service.ConferenceWebhookEvent.RoomStarted
import webchat.backend.conferences.domain.service.ConferenceWebhookEvent.Unhandled
import java.time.Instant
import java.util.UUID

/**
 * T054 unit regression (tasks.md US5): the webhook processing legs of
 * [ConferenceLifecycleService] pinned against
 * livekit-integration.md §5 / research.md §7 — port fakes all the way
 * down (the `ModerationServiceTest` convention; the story IT T057
 * holds the Testcontainers end-to-end legs):
 *
 *  * **`room_started`** — the eager №44 room ack is the metric-tagged
 *    no-op: no repository, no membership, no SSE (a conference starts
 *    at the FIRST actual join, US5-1/research §7);
 *  * **the first `participant_joined`** — the conditional
 *    `created→active` wins EXACTLY once and is the once-anchor of the
 *    `conference.started` SSE to the transaction's membership
 *    snapshot (realtime-conference-events.md §1) and of the started
 *    counter; the full connection identity `u:{userId}:{connId}`
 *    folds to the principal prefix before the `last_joined_at` mark;
 *    an actual join of a known principal counts
 *    `webchat_conference_joins_total{kind}` member/guest by the
 *    identity prefix;
 *  * **duplicates and stragglers** — a retried `participant_joined`
 *    (the repository's `null` no-op) transitions nothing, announces
 *    nothing and never double-counts the start; a LATE join after
 *    `room_finished` (status `ended`, roster deleted by the finalize)
 *    neither re-activates nor re-announces (constitution III,
 *    invariant 5);
 *  * **foreign rooms** — a room this backend never created is the
 *    tagged `ignored` no-op with nothing touched;
 *  * **the broken widget invariant** — a conference without its invite
 *    widget fails LOUD inside the transition (rolled back, never a
 *    silent mode guess — the resolveMode precedent);
 *  * **`participant_left`** — the `last_left_at` twin on the principal
 *    prefix, no SSE of any kind;
 *  * **`room_finished`** — the T055 finalize seat: accepted, parked,
 *    `ignored` until the finalize lands;
 *  * **isolation** — a failing fan-out never escapes the handler (the
 *    transition is durable; clients converge on the №45 refetch).
 */
@Suppress("TooManyFunctions") // one leg per §5 rule, the ModerationServiceTest convention
class ConferenceLifecycleServiceTest {
    private val conferences = Mockito.mock(ConferenceRepository::class.java)
    private val participants = Mockito.mock(ParticipantRepository::class.java)
    private val membership = Mockito.mock(ChatMembershipChecker::class.java)
    private val inviteModes = Mockito.mock(ConferenceModeReader::class.java)
    private val events = Mockito.mock(ConferenceEventPublisher::class.java)
    private val transactionManager = Mockito.mock(PlatformTransactionManager::class.java)
    private val meterRegistry = SimpleMeterRegistry()

    private val service =
        ConferenceLifecycleService(
            conferences = conferences,
            participants = participants,
            membership = membership,
            inviteModes = inviteModes,
            events = events,
            metrics = ConferenceMetrics(meterRegistry, Mockito.mock(JdbcTemplate::class.java)),
            clock = Clock { NOW },
            transactionManager = transactionManager,
        )

    private val created: Conference = Conference.create(CONFERENCE_ID, CHAT_ID, CREATOR_ID, MAX_PARTICIPANTS, NOW)

    @BeforeEach
    fun stubTheTransaction() {
        Mockito.`when`(transactionManager.getTransaction(ArgumentMatchers.any())).thenReturn(SimpleTransactionStatus())
        Mockito.`when`(membership.activeMemberIds(CHAT_ID)).thenReturn(MEMBERS)
        Mockito.`when`(inviteModes.modeOf(CONFERENCE_ID, CHAT_ID)).thenReturn(ConferenceMode.VIDEO)
        Mockito
            .`when`(participants.markJoined(anyNotNull(), anyNotNull(), anyNotNull()))
            .thenReturn(true)
    }

    @Test
    fun `room_started is the documented no-op — no state, no SSE, the ignored metric`() {
        service.onWebhook(RoomStarted(created.roomName))

        Mockito
            .verifyNoInteractions(conferences, participants, membership, inviteModes, events)
        assertThat(webhookCount(event = "room_started", result = "ignored")).isEqualTo(1.0)
        assertThat(startedCount()).isZero()
    }

    @Test
    fun `the first participant_joined wins created-to-active and announces conference started exactly once`() {
        Mockito.`when`(conferences.activate(CONFERENCE_ID, NOW)).thenReturn(created.activate(NOW))

        service.onWebhook(ParticipantJoined(created.roomName, ALICE_CONNECTION))

        // the frame reaches the SNAPSHOT roster (US5-1: members NOT in
        // the call included) with the transition's own stamp — data
        // class equality carries the whole contract payload
        Mockito
            .verify(events)
            .fanoutConferenceStarted(
                MEMBERS,
                ConferenceStartedEvent(CONFERENCE_ID, CHAT_ID, NOW, ConferenceMode.VIDEO),
            )

        // the full connection identity folds to the principal prefix
        Mockito.verify(participants).markJoined(CONFERENCE_ID, ConferenceIdentity.member(ALICE_ID).value, NOW)
        Mockito.verify(conferences).activate(CONFERENCE_ID, NOW)
        assertThat(startedCount()).isEqualTo(1.0)
        assertThat(joinCount(kind = "member")).isEqualTo(1.0)
        assertThat(webhookCount(event = "participant_joined", result = "processed")).isEqualTo(1.0)
    }

    @Test
    fun `a duplicate participant_joined transitions nothing and never re-announces`() {
        Mockito.`when`(conferences.activate(CONFERENCE_ID, NOW)).thenReturn(null)

        service.onWebhook(ParticipantJoined(created.roomName, ALICE_CONNECTION))

        Mockito
            .verify(events, Mockito.never())
            .fanoutConferenceStarted(ArgumentMatchers.anyList(), anyNotNull())
        assertThat(startedCount()).isZero()
        assertThat(joinCount(kind = "member")).isEqualTo(1.0)
        assertThat(webhookCount(event = "participant_joined", result = "processed")).isEqualTo(1.0)
    }

    @Test
    fun `a late participant_joined after room_finished neither activates nor announces nor counts`() {
        Mockito.`when`(conferences.activate(CONFERENCE_ID, NOW)).thenReturn(null)
        Mockito
            .`when`(participants.markJoined(anyNotNull(), anyNotNull(), anyNotNull()))
            .thenReturn(false)

        service.onWebhook(ParticipantJoined(created.roomName, ALICE_CONNECTION))

        Mockito
            .verify(events, Mockito.never())
            .fanoutConferenceStarted(ArgumentMatchers.anyList(), anyNotNull())
        assertThat(startedCount()).isZero()
        assertThat(joinCount(kind = "member")).isZero()
        assertThat(webhookCount(event = "participant_joined", result = "processed")).isEqualTo(1.0)
    }

    @Test
    fun `a guest join marks the guest principal and counts the guest kind`() {
        Mockito.`when`(conferences.activate(CONFERENCE_ID, NOW)).thenReturn(created.activate(NOW))
        val guestIdentity = ConferenceIdentity.guest(CONNECTION_ID).value

        service.onWebhook(ParticipantJoined(created.roomName, guestIdentity))

        Mockito.verify(participants).markJoined(CONFERENCE_ID, guestIdentity, NOW)
        assertThat(joinCount(kind = "guest")).isEqualTo(1.0)
        assertThat(joinCount(kind = "member")).isZero()
    }

    @Test
    fun `a malformed identity still processes the transition but marks and counts nothing`() {
        Mockito.`when`(conferences.activate(CONFERENCE_ID, NOW)).thenReturn(created.activate(NOW))

        service.onWebhook(ParticipantJoined(created.roomName, "x:not-a-uuid"))

        Mockito
            .verify(participants, Mockito.never())
            .markJoined(anyNotNull(), anyNotNull(), anyNotNull())
        Mockito.verify(events).fanoutConferenceStarted(ArgumentMatchers.anyList(), anyNotNull())
        assertThat(joinCount(kind = "member")).isZero()
        assertThat(joinCount(kind = "guest")).isZero()
    }

    @Test
    fun `a participant_joined of a foreign room is the tagged no-op`() {
        service.onWebhook(ParticipantJoined("somebody-elses-room", ALICE_CONNECTION))

        Mockito.verifyNoInteractions(conferences, participants, membership, inviteModes, events)
        assertThat(webhookCount(event = "participant_joined", result = "ignored")).isEqualTo(1.0)
    }

    @Test
    fun `a conference without its invite widget fails loud inside the transition`() {
        Mockito.`when`(conferences.activate(CONFERENCE_ID, NOW)).thenReturn(created.activate(NOW))
        Mockito.`when`(inviteModes.modeOf(CONFERENCE_ID, CHAT_ID)).thenReturn(null)

        assertThatThrownBy { service.onWebhook(ParticipantJoined(created.roomName, ALICE_CONNECTION)) }
            .isInstanceOf(IllegalStateException::class.java)

        Mockito
            .verify(events, Mockito.never())
            .fanoutConferenceStarted(ArgumentMatchers.anyList(), anyNotNull())
        assertThat(startedCount()).isZero()
    }

    @Test
    fun `participant_left marks the principal prefix with no SSE of any kind`() {
        service.onWebhook(ParticipantLeft(created.roomName, ALICE_CONNECTION))

        Mockito.verify(participants).markLeft(CONFERENCE_ID, ConferenceIdentity.member(ALICE_ID).value, NOW)
        Mockito.verifyNoInteractions(conferences, membership, inviteModes, events)
        assertThat(webhookCount(event = "participant_left", result = "processed")).isEqualTo(1.0)
    }

    @Test
    fun `participant_left of an unresolvable target is ignored`() {
        service.onWebhook(ParticipantLeft(created.roomName, "garbage"))

        Mockito.verifyNoInteractions(participants)
        assertThat(webhookCount(event = "participant_left", result = "ignored")).isEqualTo(1.0)
    }

    @Test
    fun `room_finished is parked for the finalize of T055`() {
        service.onWebhook(RoomFinished(created.roomName))

        Mockito.verifyNoInteractions(conferences, participants, membership, inviteModes, events)
        assertThat(webhookCount(event = "room_finished", result = "ignored")).isEqualTo(1.0)
    }

    @Test
    fun `an unhandled event type folds to the other tag`() {
        service.onWebhook(Unhandled("track_published"))

        Mockito.verifyNoInteractions(conferences, participants, membership, inviteModes, events)
        assertThat(webhookCount(event = "other", result = "ignored")).isEqualTo(1.0)
    }

    @Test
    fun `a failing fan-out never escapes the handler`() {
        Mockito.`when`(conferences.activate(CONFERENCE_ID, NOW)).thenReturn(created.activate(NOW))
        Mockito
            .`when`(events.fanoutConferenceStarted(ArgumentMatchers.anyList(), anyNotNull()))
            .thenThrow(IllegalStateException("redis is down"))

        service.onWebhook(ParticipantJoined(created.roomName, ALICE_CONNECTION))

        assertThat(startedCount()).isEqualTo(1.0)
        assertThat(webhookCount(event = "participant_joined", result = "processed")).isEqualTo(1.0)
    }

    private fun startedCount(): Double = meterRegistry.find(STARTED_METRIC).counter()?.count() ?: 0.0

    private fun joinCount(kind: String): Double =
        meterRegistry
            .find(JOINS_METRIC)
            .tag(KIND_TAG, kind)
            .counter()
            ?.count()
            ?: 0.0

    private fun webhookCount(
        event: String,
        result: String,
    ): Double =
        meterRegistry
            .find(WEBHOOKS_PROCESSED_METRIC)
            .tag(EVENT_TAG, event)
            .tag(RESULT_TAG, result)
            .counter()
            ?.count()
            ?: 0.0

    /**
     * The Kotlin null-safety seam of Mockito on the conferences Kotlin
     * ports (the ConferenceServiceTest trick, inlined for this file).
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T> anyNotNull(): T = ArgumentMatchers.any() as T

    private companion object {
        val NOW: Instant = Instant.parse("2026-10-18T12:00:00Z")

        val CONFERENCE_ID: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000aa")

        val CHAT_ID: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000bb")

        val CREATOR_ID: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000cc")

        val ALICE_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")

        val CONNECTION_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000002")

        const val MAX_PARTICIPANTS = 16

        /** carol included — the SSE reaches chat members NOT in the call (US5-1). */
        val MEMBERS: List<UUID> = listOf(ALICE_ID, CREATOR_ID)

        /** a member CONNECTION identity — what the webhook actually carries (research §4). */
        val ALICE_CONNECTION: String = "u:$ALICE_ID:$CONNECTION_ID"

        const val STARTED_METRIC = "webchat_conference_started_total"

        const val JOINS_METRIC = "webchat_conference_joins_total"

        const val WEBHOOKS_PROCESSED_METRIC = "webchat_conference_webhooks_processed_total"

        const val KIND_TAG = "kind"

        const val EVENT_TAG = "event"

        const val RESULT_TAG = "result"
    }
}
