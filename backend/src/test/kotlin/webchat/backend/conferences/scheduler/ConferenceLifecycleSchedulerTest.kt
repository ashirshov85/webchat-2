package webchat.backend.conferences.scheduler

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import org.springframework.jdbc.core.JdbcTemplate
import webchat.backend.auth.domain.port.Clock
import webchat.backend.conferences.ConferenceMetrics
import webchat.backend.conferences.domain.model.Conference
import webchat.backend.conferences.domain.model.ConferenceEndReason
import webchat.backend.conferences.domain.model.ConferenceStatus
import webchat.backend.conferences.domain.port.ConferenceMediaUnavailableException
import webchat.backend.conferences.domain.port.ConferenceRepository
import webchat.backend.conferences.domain.port.LiveKitAdminClient
import webchat.backend.conferences.domain.port.LiveKitParticipant
import webchat.backend.conferences.domain.service.ConferenceLifecycleService
import webchat.backend.config.ConferencesProperties
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * T056 unit regression (tasks.md US5): the TIME legs of
 * [ConferenceLifecycleScheduler] pinned against research.md §7 /
 * livekit-integration.md §4 — port fakes all the way down (the
 * `ConferenceLifecycleServiceTest` convention; the story IT T057
 * holds the Testcontainers end-to-end legs):
 *
 *  * **max-duration (Q16=B)** — inside the `warning-before-end` window
 *    the ONE `SendData` packet fires (topic `conference.warning`,
 *    payload with the horizon), and ONLY once per instance; a failed
 *    packet retries on the next tick (at-least-once for a toast,
 *    never a per-tick spam); an absent room warns nobody; at the
 *    horizon the finalize pre-record (`max_duration`) lands BEFORE
 *    `DeleteRoom` — the №48 order, so the deletion's webhook re-enters
 *    finalize as the invariant-5 no-op; the race loser leaves the room
 *    legs to the winner; a `DeleteRoom` failure never escapes the
 *    committed end;
 *  * **sweeper (SC-007)** — an `active` room absent past
 *    `room-lost-grace` finalizes `room_lost`; the grace clock runs on
 *    the sweeper's OWN observations and RESETS when the room returns;
 *    a `created` room absent while the empty timeout could not have
 *    eaten it is re-created with the row's own snapshot (the crash
 *    orphan self-heal), past that border it finalizes `room_lost`
 *    («никто не вошёл»); a live room is left alone;
 *  * **degradation** — an unreachable plane skips every observation
 *    leg (no warning, no re-creation, no `room_lost`) yet the
 *    max-duration horizon STILL ends its conference; a poisoned row
 *    isolates to itself and the rest of the batch still runs;
 *  * **the gauge** — `webchat_conference_participants_connected`
 *    rides the room-by-room sum of the same scan (research §13).
 */
@Suppress("TooManyFunctions") // one leg per §7 duty, the ConferenceLifecycleServiceTest convention
class ConferenceLifecycleSchedulerTest {
    private val conferences = Mockito.mock(ConferenceRepository::class.java)
    private val lifecycle = Mockito.mock(ConferenceLifecycleService::class.java)
    private val liveKit = Mockito.mock(LiveKitAdminClient::class.java)
    private val meterRegistry = SimpleMeterRegistry()

    /** The mutable now() the grace/window ladders walk between sweeps. */
    private var now: Instant = T0

    private val scheduler =
        ConferenceLifecycleScheduler(
            conferences = conferences,
            lifecycle = lifecycle,
            liveKit = liveKit,
            properties = TEST_PROPERTIES,
            metrics = ConferenceMetrics(meterRegistry, Mockito.mock(JdbcTemplate::class.java)),
            clock = Clock { now },
        )

    /** An ACTIVE conference started at T0 — the horizon is T0 + 4 h (DEADLINE). */
    private val active: Conference =
        Conference.create(CONFERENCE_ID, CHAT_ID, CREATOR_ID, MAX_PARTICIPANTS, T0).activate(T0)!!

    /** A CREATED conference born at T0 — nobody ever joined (its room is the eager №44 one). */
    private val created: Conference = Conference.create(CREATED_ID, CHAT_ID, CREATOR_ID, MAX_PARTICIPANTS, T0)

    @Test
    fun `the max-duration warning fires once inside the window and carries the horizon payload`() {
        stubActive(active)
        Mockito.`when`(liveKit.listRoomNames()).thenReturn(setOf(active.roomName))
        now = DEADLINE.minus(Duration.ofMinutes(3))

        scheduler.sweep()
        scheduler.sweep()

        Mockito
            .verify(liveKit, Mockito.times(1))
            .sendData(active.roomName, WARNING_TOPIC, """{"type":"conference.warning","endsAt":"$DEADLINE"}""")
        Mockito
            .verify(lifecycle, Mockito.never())
            .finalize(anyNotNull(), anyNotNull())
    }

    @Test
    fun `a failed warning packet un-marks the conference and the next tick retries it`() {
        stubActive(active)
        Mockito.`when`(liveKit.listRoomNames()).thenReturn(setOf(active.roomName))
        Mockito
            .`when`(liveKit.sendData(anyNotNull(), anyNotNull(), anyNotNull()))
            .thenThrow(ConferenceMediaUnavailableException(RuntimeException("plane blip")))
        now = DEADLINE.minus(Duration.ofMinutes(3))

        scheduler.sweep()
        now = now.plus(Duration.ofSeconds(10))
        scheduler.sweep()

        // the second call happening at all IS the un-mark proof — a
        // once-marked conference would never reach the packet again
        Mockito.verify(liveKit, Mockito.times(2)).sendData(anyNotNull(), anyNotNull(), anyNotNull())
    }

    @Test
    fun `an in-window conference without a room warns nobody`() {
        stubActive(active)
        Mockito.`when`(liveKit.listRoomNames()).thenReturn(emptySet())
        now = DEADLINE.minus(Duration.ofMinutes(3))

        scheduler.sweep()

        Mockito
            .verify(liveKit, Mockito.never())
            .sendData(anyNotNull(), anyNotNull(), anyNotNull())
    }

    @Test
    fun `the max-duration horizon finalizes before DeleteRoom — the No48 order`() {
        stubActive(active)
        Mockito.`when`(liveKit.listRoomNames()).thenReturn(setOf(active.roomName))
        Mockito
            .`when`(lifecycle.finalize(active, ConferenceEndReason.MAX_DURATION))
            .thenReturn(active.end(ConferenceEndReason.MAX_DURATION, now))
        now = DEADLINE.plus(Duration.ofSeconds(1))

        scheduler.sweep()

        val order = Mockito.inOrder(lifecycle, liveKit)
        order.verify(lifecycle).finalize(active, ConferenceEndReason.MAX_DURATION)
        order.verify(liveKit).deleteRoom(active.roomName)
    }

    @Test
    fun `a losing max-duration finalize leaves the room legs to the winner`() {
        stubActive(active)
        Mockito
            .`when`(lifecycle.finalize(active, ConferenceEndReason.MAX_DURATION))
            .thenReturn(null)
        now = DEADLINE.plus(Duration.ofSeconds(1))

        scheduler.sweep()

        Mockito.verify(lifecycle).finalize(active, ConferenceEndReason.MAX_DURATION)
        Mockito.verify(liveKit, Mockito.never()).deleteRoom(anyNotNull())
    }

    @Test
    fun `a DeleteRoom failure after the committed max-duration end never escapes the tick`() {
        stubActive(active)
        Mockito
            .`when`(lifecycle.finalize(active, ConferenceEndReason.MAX_DURATION))
            .thenReturn(active.end(ConferenceEndReason.MAX_DURATION, now))
        Mockito
            .`when`(liveKit.deleteRoom(anyNotNull()))
            .thenThrow(ConferenceMediaUnavailableException(RuntimeException("plane down")))
        now = DEADLINE.plus(Duration.ofSeconds(1))

        assertThatCode { scheduler.sweep() }.doesNotThrowAnyException()

        Mockito.verify(lifecycle).finalize(active, ConferenceEndReason.MAX_DURATION)
    }

    @Test
    fun `an active room absent past the grace finalizes room_lost — not a tick earlier`() {
        stubActive(active)
        Mockito.`when`(liveKit.listRoomNames()).thenReturn(emptySet())

        scheduler.sweep()
        now = T0.plus(Duration.ofSeconds(119))
        scheduler.sweep()
        Mockito
            .verify(lifecycle, Mockito.never())
            .finalize(anyNotNull(), anyNotNull())

        now = T0.plus(Duration.ofSeconds(121))
        scheduler.sweep()
        Mockito.verify(lifecycle).finalize(active, ConferenceEndReason.ROOM_LOST)
    }

    @Test
    fun `a room that returns resets the grace clock of its absence`() {
        stubActive(active)
        Mockito.`when`(liveKit.listRoomNames()).thenReturn(emptySet())
        scheduler.sweep() // the absence clock starts at T0

        Mockito.`when`(liveKit.listRoomNames()).thenReturn(setOf(active.roomName))
        now = T0.plus(Duration.ofSeconds(60))
        scheduler.sweep() // the room is back — the clock clears

        Mockito.`when`(liveKit.listRoomNames()).thenReturn(emptySet())
        now = T0.plus(Duration.ofSeconds(61))
        scheduler.sweep() // a FRESH absence clock starts here

        // 121 s after the FIRST absence (past the grace) but only 119 s
        // after the fresh one — nothing yet
        now = T0.plus(Duration.ofSeconds(180))
        scheduler.sweep()
        Mockito
            .verify(lifecycle, Mockito.never())
            .finalize(anyNotNull(), anyNotNull())

        now = T0.plus(Duration.ofSeconds(182))
        scheduler.sweep()
        Mockito.verify(lifecycle).finalize(active, ConferenceEndReason.ROOM_LOST)
    }

    @Test
    fun `a created room missing inside the empty timeout is re-created with the row's own snapshot`() {
        stubCreated(created)
        Mockito.`when`(liveKit.listRoomNames()).thenReturn(emptySet())
        now = T0.plus(Duration.ofSeconds(30))

        scheduler.sweep()

        Mockito
            .verify(liveKit)
            .createRoom(
                name = created.roomName,
                emptyTimeout = EMPTY_ROOM_TIMEOUT,
                departureTimeout = DEPARTURE_TIMEOUT,
                maxParticipants = created.maxParticipants,
            )
        Mockito
            .verify(lifecycle, Mockito.never())
            .finalize(anyNotNull(), anyNotNull())
    }

    @Test
    fun `a created room missing past the empty timeout finalizes room_lost — nobody ever joined`() {
        stubCreated(created)
        Mockito.`when`(liveKit.listRoomNames()).thenReturn(emptySet())
        now = T0.plus(Duration.ofSeconds(61))

        scheduler.sweep()

        Mockito.verify(lifecycle).finalize(created, ConferenceEndReason.ROOM_LOST)
        verifyNeverRecreatedARoom()
    }

    @Test
    fun `a created conference with its live room is left alone`() {
        stubCreated(created)
        Mockito.`when`(liveKit.listRoomNames()).thenReturn(setOf(created.roomName))

        scheduler.sweep()

        Mockito.verifyNoInteractions(lifecycle)
        verifyNeverRecreatedARoom()
    }

    @Test
    fun `an unreachable plane skips the observation legs but still ends the expired conference`() {
        // started so the horizon is DEADLINE + 4 min — INSIDE the warning
        // window at the sweep moment below: the conference that WOULD
        // have been warned, proving the plane-down skip
        val inWindow =
            Conference
                .create(IN_WINDOW_ID, CHAT_ID, CREATOR_ID, MAX_PARTICIPANTS, T0)
                .activate(DEADLINE.plus(Duration.ofMinutes(4)).minus(TEST_PROPERTIES.maxDuration))!!
        stubActive(active, inWindow)
        stubCreated(created)
        Mockito
            .`when`(liveKit.listRoomNames())
            .thenThrow(ConferenceMediaUnavailableException(RuntimeException("plane down")))
        Mockito
            .`when`(lifecycle.finalize(active, ConferenceEndReason.MAX_DURATION))
            .thenReturn(active.end(ConferenceEndReason.MAX_DURATION, now))
        now = DEADLINE.plus(Duration.ofSeconds(1))

        assertThatCode { scheduler.sweep() }.doesNotThrowAnyException()

        // the horizon is DB truth — it ran; everything observational skipped
        Mockito.verify(lifecycle).finalize(active, ConferenceEndReason.MAX_DURATION)
        Mockito.verifyNoMoreInteractions(lifecycle)
        Mockito
            .verify(liveKit, Mockito.never())
            .sendData(anyNotNull(), anyNotNull(), anyNotNull())
        verifyNeverRecreatedARoom()
        assertThat(participantsGauge()).isZero()
    }

    @Test
    fun `a poisoned row isolates to itself — the rest of the batch still runs`() {
        val poisoned =
            Conference.create(POISONED_ID, CHAT_ID, CREATOR_ID, MAX_PARTICIPANTS, T0).activate(T0)!!
        stubActive(poisoned, active)
        Mockito.`when`(liveKit.listRoomNames()).thenReturn(emptySet())
        Mockito
            .`when`(lifecycle.finalize(poisoned, ConferenceEndReason.MAX_DURATION))
            .thenThrow(IllegalStateException("repository hiccup"))
        Mockito
            .`when`(lifecycle.finalize(active, ConferenceEndReason.MAX_DURATION))
            .thenReturn(active.end(ConferenceEndReason.MAX_DURATION, now))
        now = DEADLINE.plus(Duration.ofSeconds(1))

        assertThatCode { scheduler.sweep() }.doesNotThrowAnyException()

        Mockito.verify(lifecycle).finalize(active, ConferenceEndReason.MAX_DURATION)
        Mockito.verify(liveKit).deleteRoom(active.roomName)
    }

    @Test
    fun `the participants gauge rides the room-by-room sum of the same scan`() {
        stubActive(active)
        stubCreated(created)
        Mockito
            .`when`(liveKit.listRoomNames())
            .thenReturn(setOf(active.roomName, created.roomName))
        Mockito
            .`when`(liveKit.listParticipants(active.roomName))
            .thenReturn(
                listOf(
                    LiveKitParticipant("u:$CREATOR_ID:${UUID.randomUUID()}", null),
                    LiveKitParticipant("g:${UUID.randomUUID()}", null),
                ),
            )
        Mockito
            .`when`(liveKit.listParticipants(created.roomName))
            .thenReturn(listOf(LiveKitParticipant("g:${UUID.randomUUID()}", null)))

        scheduler.sweep()

        assertThat(participantsGauge()).isEqualTo(3.0)
    }

    private fun stubActive(vararg rows: Conference) {
        Mockito.`when`(conferences.findByStatus(ConferenceStatus.ACTIVE)).thenReturn(rows.toList())
    }

    private fun stubCreated(vararg rows: Conference) {
        Mockito.`when`(conferences.findByStatus(ConferenceStatus.CREATED)).thenReturn(rows.toList())
    }

    private fun verifyNeverRecreatedARoom() {
        Mockito
            .verify(liveKit, Mockito.never())
            .createRoom(anyNotNull(), anyNotNull(), anyNotNull(), ArgumentMatchers.anyInt())
    }

    private fun participantsGauge(): Double = meterRegistry.find(PARTICIPANTS_CONNECTED_METRIC).gauge()?.value() ?: 0.0

    /**
     * The Kotlin null-safety seam of Mockito on the conferences Kotlin
     * ports (the ConferenceLifecycleServiceTest trick, inlined here).
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T> anyNotNull(): T = ArgumentMatchers.any() as T

    private companion object {
        val T0: Instant = Instant.parse("2026-10-18T12:00:00Z")

        const val MAX_PARTICIPANTS = 16

        val TEST_PROPERTIES =
            ConferencesProperties(
                maxParticipants = MAX_PARTICIPANTS,
                tokenTtl = Duration.ofSeconds(900),
                guestInviteTtl = Duration.ofHours(24),
                emptyRoomTimeout = Duration.ofSeconds(60),
                departureTimeout = Duration.ofSeconds(60),
                maxDuration = Duration.ofHours(4),
                warningBeforeEnd = Duration.ofMinutes(5),
                reconnectWindow = Duration.ofSeconds(120),
                guestNameMaxLength = 50,
                sweepInterval = Duration.ofSeconds(60),
                roomLostGrace = Duration.ofSeconds(120),
                livekit = ConferencesProperties.LiveKit("http://localhost:7880", "devkey", "secret"),
                rateLimit = ConferencesProperties.RateLimit("30/1m", "10/1m", "60/1m"),
            )

        /** `T0 + max-duration` — the horizon every ladder walks to. */
        val DEADLINE: Instant = T0.plus(TEST_PROPERTIES.maxDuration)

        val EMPTY_ROOM_TIMEOUT: Duration = TEST_PROPERTIES.emptyRoomTimeout

        val DEPARTURE_TIMEOUT: Duration = TEST_PROPERTIES.departureTimeout

        const val WARNING_TOPIC = "conference.warning"

        const val PARTICIPANTS_CONNECTED_METRIC = "webchat_conference_participants_connected"

        val CONFERENCE_ID: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000aa")

        val CREATED_ID: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000ab")

        val IN_WINDOW_ID: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000ac")

        val POISONED_ID: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000ad")

        val CHAT_ID: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000bb")

        val CREATOR_ID: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000cc")
    }
}
