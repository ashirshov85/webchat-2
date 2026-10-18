package webchat.backend.conferences

import com.fasterxml.jackson.databind.JsonNode
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.test.context.TestPropertySource
import webchat.backend.auth.domain.port.Clock
import webchat.backend.conferences.domain.port.ConferenceRepository
import webchat.backend.conferences.domain.port.LiveKitAdminClient
import webchat.backend.conferences.domain.service.ConferenceLifecycleService
import webchat.backend.conferences.livekit.LiveKitServerAdminClient
import webchat.backend.conferences.scheduler.ConferenceLifecycleScheduler
import webchat.backend.config.ConferencesProperties
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * The inert context-separating knob of [ConferenceLifecycleIT] — the
 * ConferenceGuestAccessIT convention: [ConferenceCreateJoinIT]'s last
 * leg KILLS the shared plane, and a class sharing its cached context
 * would keep the dead container's URL; this DISTINCT context
 * configuration re-runs the self-healing `conferences.livekit.url`
 * supplier and binds the restarted plane (the support KDoc's
 * «later US4/US5 sets» rule). The value itself is inert —
 * `reconnect-window` is a client budget no leg of this class reads;
 * only the context key matters (and it differs from the US4 knob, so
 * the class never rides a context another suite may still need).
 */
private const val CONTEXT_SEPARATING_PROPERTY = "conferences.reconnect-window=118s"

/**
 * T057 (tasks.md Phase 7, US5) — the story IT of the lifecycle half:
 * the webhook intake (T053), the T054 processing, the T055 finalize
 * and the T056 scheduler, pinned end-to-end against the REAL stack
 * (Testcontainers PG + Redis + the [ConferencesTestSupport] media
 * plane). One pinned leg per rule of the task table:
 *
 *  * **the intake boundary (§5)** — an invalid signature (no header,
 *    a foreign key, a tampered body) answers the uniform 401; a valid
 *    one answers 200 ALWAYS;
 *  * **US5-1 / SC-006** — №44 announces NOTHING (the eager room is
 *    not a start, research §7); the FIRST `participant_joined` of a
 *    `created` conference wins the conditional `created→active` and
 *    the ONE `conference.started` frame to EVERY active chat member —
 *    the one who joined and the one who never opened the call alike —
 *    delivered ≤ 2 s; a duplicate delivery (a fresh `event.id`, so the
 *    Redis dedup cannot swallow it) re-announces nothing;
 *  * **invariant 5** — duplicate `room_finished` deliveries keep the
 *    FIRST winner's `ended_at`/`end_reason` verbatim (the conditional
 *    `→ended` UPDATE is the idempotency seat, not the dedup window);
 *  * **invariant 6 / Q18/Q20=A** — after the finalize the participant
 *    and invite rows are gone, the №15 history is untouched, and BOTH
 *    old-link paths answer «конференция завершена»: member №47 →
 *    `409 conference_ended`, guest №54 → `200 {valid:false,
 *    reason:"ended"}` (the ended row is the only surviving witness of
 *    the deleted invite), №55 → `409 conference_ended`;
 *  * **constitution III** — a «late» out-of-order `participant_joined`
 *    landing after `room_finished` re-activates nothing and re-announces
 *    nothing (forward-only transitions, at-most-once frames);
 *  * **SC-007 / the empty-timeout** — a `created` conference nobody
 *    joined ends `room_lost` past the empty-room border once the
 *    sweeper observes the room gone (the lost-webhook compensation),
 *    with the SSE `reason='room_lost'`;
 *  * **Q16=B** — max-duration: the ONE warning packet
 *    (`conference.warning` with the horizon payload) accepted by the
 *    REAL plane inside the window, then the controlled end
 *    (`max_duration`) with the room deleted and the live connection
 *    broken server-side;
 *  * **SC-007 / the stopped plane** — while the plane is DOWN the
 *    sweeper observes nothing and finalizes nothing (the documented
 *    degradation); once the plane is BACK (a fresh room set — exactly
 *    the post-crash shape) the grace clock of the absence starts and
 *    the hung call ends `room_lost`.
 *
 * The lifecycle events arrive as SIGNED webhook deliveries through
 * [postWebhook] — the test container runs the plane WITHOUT a webhook
 * config (the compose shape only), which is what makes every intake
 * leg (signature, dedup, idempotency, out-of-order) deterministic.
 * The scheduler legs run through a DIRECTLY CONSTRUCTED
 * [ConferenceLifecycleScheduler] (application-test.yml disables the
 * scheduled bean JVM-wide against the ONE shared plane — the presence
 * T036 single-owner rule; the IT is the single owner driving its
 * ticks), so no background sweep ever races the assertions.
 *
 * The last leg KILLS and restarts the shared plane, so it runs last
 * (the ConferenceCreateJoinIT convention; the DynamicPropertySource
 * supplier self-heals any later context).
 */
@TestMethodOrder(OrderAnnotation::class)
@TestPropertySource(properties = [CONTEXT_SEPARATING_PROPERTY])
@Suppress("TooManyFunctions") // one method per rule of the T057 task table
class ConferenceLifecycleIT : ConferencesTestSupport() {
    @Autowired
    private lateinit var conferencesRepository: ConferenceRepository

    @Autowired
    private lateinit var lifecycleService: ConferenceLifecycleService

    @Autowired
    private lateinit var conferencesProperties: ConferencesProperties

    @Autowired
    private lateinit var conferenceMetrics: ConferenceMetrics

    @Autowired
    private lateinit var clock: Clock

    /** The T062 durable-reason probe — the winning-finalize counter the CASCADEd-away row cannot carry. */
    @Autowired
    private lateinit var meterRegistry: MeterRegistry

    /** §5: the three rejection modes of the receiver fold into the uniform 401; a valid signature is 200-always. */
    @Order(1)
    @Test
    fun `an invalidly signed webhook is refused 401 while a valid signature answers 200`() {
        val sourceIp = uniqueSourceIp()
        val apiKey = conferencesProperties.livekit.apiKey
        val apiSecret = conferencesProperties.livekit.apiSecret
        val body = roomEventBody(ROOM_STARTED_EVENT, roomNameOf(UUID.randomUUID()))

        val unsigned = postWebhook(body, authHeader = null, sourceIp = sourceIp)
        assertThat(unsigned.statusCode)
            .overridingErrorMessage("a webhook without an Authorization header must answer the uniform 401")
            .isEqualTo(HttpStatus.UNAUTHORIZED)

        val foreignKey =
            postWebhook(
                body,
                signedWebhookAuth(body, apiKey, "another-planes-secret-0123456789abcdef"),
                sourceIp,
            )
        assertThat(foreignKey.statusCode)
            .overridingErrorMessage("a webhook signed with a foreign key must answer the uniform 401")
            .isEqualTo(HttpStatus.UNAUTHORIZED)

        val tampered =
            postWebhook(
                // a DIFFERENT event id under the signature of the original bytes — the sha mismatch leg
                roomEventBody(ROOM_STARTED_EVENT, roomNameOf(UUID.randomUUID())),
                signedWebhookAuth(body, apiKey, apiSecret),
                sourceIp,
            )
        assertThat(tampered.statusCode)
            .overridingErrorMessage("a tampered body under a valid token must answer the uniform 401")
            .isEqualTo(HttpStatus.UNAUTHORIZED)

        val valid = postWebhook(body, signedWebhookAuth(body, apiKey, apiSecret), sourceIp)
        assertThat(valid.statusCode)
            .overridingErrorMessage("a validly signed delivery must answer 200 always (§5 «Доступность»)")
            .isEqualTo(HttpStatus.OK)
    }

    /** US5-1/SC-006: started fires ONCE — at the first real join, never at №44 — ≤ 2 s to every member. */
    @Order(2)
    @Test
    fun `conference started fires once at the first participant_joined and never at creation`() {
        val (alice, bob) = messagingPair()
        val chatId = ensureChatOk(alice, bob.id)
        val sourceIp = uniqueSourceIp()

        openUserEvents(alice).use { aliceStream ->
            openUserEvents(bob).use { bobStream ->
                val conferenceId = createConferenceOk(alice, chatId)
                val room = roomNameOf(conferenceId)
                val ticket = ticketOf(joinConferenceOk(bob, conferenceId))
                val identity = ticket[IDENTITY_FIELD].asText()
                connectTicket(ticket)
                awaitRoomParticipants(room) { it.isNotEmpty() }

                // US5-1: the eager №44 room (and its widget) announced nothing — research §7
                assertNoConferenceEvent(aliceStream, CONFERENCE_STARTED_EVENT, NO_EVENT_WINDOW)
                assertNoConferenceEvent(bobStream, CONFERENCE_STARTED_EVENT, NO_EVENT_WINDOW)

                val postedAt = System.nanoTime()
                val delivered =
                    deliverWebhook(participantJoinedBody(room, identity), sourceIp)
                assertThat(delivered.statusCode).isEqualTo(HttpStatus.OK)

                for ((member, stream) in listOf(alice to aliceStream, bob to bobStream)) {
                    val started = stream.awaitEvent(CONFERENCE_STARTED_EVENT, DELIVERY_BUDGET)
                    assertThat(Duration.ofNanos(System.nanoTime() - postedAt))
                        .overridingErrorMessage(
                            "SC-006: conference.started must reach <%s> (a chat member, in the call or not) within 2 s",
                            member.username,
                        ).isLessThanOrEqualTo(SC_006)
                    assertStartedPayload(started, conferenceId, chatId, VIDEO_MODE)
                }
                val row = conferenceRow(conferenceId)
                assertThat(row[STATUS_COLUMN]).isEqualTo(ACTIVE_STATUS)
                assertThat(row[STARTED_AT_COLUMN]).isNotNull

                // a duplicate participant_joined under a FRESH event.id — the Redis dedup
                // cannot swallow it, only the conditional transition can (US5-1)
                val startedAt = row[STARTED_AT_COLUMN]
                assertThat(
                    deliverWebhook(participantJoinedBody(room, identity), sourceIp).statusCode,
                ).isEqualTo(HttpStatus.OK)
                assertThat(conferenceRow(conferenceId)[STARTED_AT_COLUMN])
                    .overridingErrorMessage("a duplicate join must not move started_at")
                    .isEqualTo(startedAt)
                assertNoConferenceEvent(aliceStream, CONFERENCE_STARTED_EVENT, NO_EVENT_WINDOW)
            }
        }
    }

    /** Invariant 5: duplicate room_finished deliveries keep the first winner's ended_at and end_reason. */
    @Order(3)
    @Test
    fun `duplicate room_finished deliveries keep the first winner's end`() {
        val (alice, bob) = messagingPair()
        val chatId = ensureChatOk(alice, bob.id)
        val sourceIp = uniqueSourceIp()
        val conferenceId = createConferenceOk(alice, chatId)
        activateByWebhook(conferenceId, sourceIp)

        openUserEvents(bob).use { bobStream ->
            val postedAt = System.nanoTime()
            assertThat(deliverWebhook(roomFinishedBody(roomNameOf(conferenceId)), sourceIp).statusCode)
                .isEqualTo(HttpStatus.OK)

            val ended = bobStream.awaitEvent(CONFERENCE_ENDED_EVENT, DELIVERY_BUDGET)
            assertThat(Duration.ofNanos(System.nanoTime() - postedAt))
                .overridingErrorMessage("SC-006: conference.ended must arrive within 2 s of the finalize")
                .isLessThanOrEqualTo(SC_006)
            assertEndedPayload(ended, conferenceId, chatId, LAST_LEFT_REASON)

            val winner = conferenceRow(conferenceId)
            assertThat(winner[STATUS_COLUMN]).isEqualTo(ENDED_STATUS)
            assertThat(winner[END_REASON_COLUMN]).isEqualTo(LAST_LEFT_REASON)
            assertThat(winner[ENDED_AT_COLUMN]).isNotNull

            // the plane redelivers room_finished under a FRESH event.id — the dedup
            // cannot swallow it, only the conditional →ended UPDATE can (invariant 5)
            assertThat(deliverWebhook(roomFinishedBody(roomNameOf(conferenceId)), sourceIp).statusCode)
                .isEqualTo(HttpStatus.OK)
            val still = conferenceRow(conferenceId)
            assertThat(still[ENDED_AT_COLUMN])
                .overridingErrorMessage("a duplicate room_finished must not move ended_at (invariant 5)")
                .isEqualTo(winner[ENDED_AT_COLUMN])
            assertThat(still[END_REASON_COLUMN])
                .overridingErrorMessage("a duplicate room_finished must not move end_reason (invariant 5)")
                .isEqualTo(LAST_LEFT_REASON)
            assertNoConferenceEvent(bobStream, CONFERENCE_ENDED_EVENT, NO_EVENT_WINDOW)
        }
    }

    /** Invariant 6 / Q18/Q20=A: the roster gone, the history intact, every old link answers ended. */
    @Order(4)
    @Test
    fun `after finalize the roster is gone the history intact and the old links answer ended`() {
        val (alice, bob) = messagingPair()
        val chatId = ensureChatOk(alice, bob.id)
        val sourceIp = uniqueSourceIp()
        val conferenceId = createConferenceOk(alice, chatId)
        val nonce = nonceOfInviteUrl(createGuestInviteOk(alice, conferenceId)[INVITE_URL_FIELD].asText())
        activateByWebhook(conferenceId, sourceIp)
        val historyBefore = messageCountOf(chatId)
        val widgetsBefore = systemMessageRowsOf(chatId).size

        openUserEvents(bob).use { bobStream ->
            assertThat(deliverWebhook(roomFinishedBody(roomNameOf(conferenceId)), sourceIp).statusCode)
                .isEqualTo(HttpStatus.OK)
            assertEndedPayload(
                bobStream.awaitEvent(CONFERENCE_ENDED_EVENT, DELIVERY_BUDGET),
                conferenceId,
                chatId,
                LAST_LEFT_REASON,
            )

            // Q18=A: neither the roster nor the invites of a finished conference are kept
            assertThat(participantRowsOf(conferenceId)).isEmpty()
            assertThat(inviteRowsOf(conferenceId)).isEmpty()

            // Q20=A: the trace of the end is the SSE frame and the №45 status — never a history row
            assertThat(messageCountOf(chatId)).isEqualTo(historyBefore)
            assertThat(systemMessageRowsOf(chatId)).hasSize(widgetsBefore)

            val view = conferenceViewOf(getConference(alice, conferenceId))
            assertThat(view[STATUS_FIELD].asText()).isEqualTo(ENDED_STATUS)

            val memberJoin = joinConference(bob, conferenceId)
            assertThat(memberJoin.statusCode).isEqualTo(HttpStatus.CONFLICT)
            assertThat(problemCode(memberJoin)).isEqualTo(CONFERENCE_ENDED_CODE)

            // №54: the ended row is the ONLY surviving witness of the deleted invite row
            val guestView = objectMapper.readTree(inviteInfo(nonce, uniqueSourceIp()).body)
            assertThat(guestView[VALID_FIELD].asBoolean()).isFalse
            assertThat(guestView[REASON_FIELD].asText()).isEqualTo(ENDED_REASON)

            val guestJoin = joinByInvite(nonce, "late-guest", uniqueSourceIp())
            assertThat(guestJoin.statusCode).isEqualTo(HttpStatus.CONFLICT)
            assertThat(problemCode(guestJoin)).isEqualTo(CONFERENCE_ENDED_CODE)
        }
    }

    /** Constitution III: a late participant_joined after room_finished re-activates and re-announces nothing. */
    @Order(5)
    @Test
    fun `a late participant_joined after room_finished never re-activates nor re-announces`() {
        val (alice, bob) = messagingPair()
        val chatId = ensureChatOk(alice, bob.id)
        val sourceIp = uniqueSourceIp()
        val conferenceId = createConferenceOk(alice, chatId)

        openUserEvents(bob).use { bobStream ->
            // the stream must predate the transition — the frame is at-most-once
            activateByWebhook(conferenceId, sourceIp)
            bobStream.awaitEvent(CONFERENCE_STARTED_EVENT, DELIVERY_BUDGET)
            assertThat(deliverWebhook(roomFinishedBody(roomNameOf(conferenceId)), sourceIp).statusCode)
                .isEqualTo(HttpStatus.OK)
            bobStream.awaitEvent(CONFERENCE_ENDED_EVENT, DELIVERY_BUDGET)
            val ended = conferenceRow(conferenceId)

            // the straggler: the plane redelivered a join of the dead room AFTER the end
            assertThat(
                deliverWebhook(
                    participantJoinedBody(roomNameOf(conferenceId), "u:${bob.id}:${UUID.randomUUID()}"),
                    sourceIp,
                ).statusCode,
            ).isEqualTo(HttpStatus.OK)

            val still = conferenceRow(conferenceId)
            assertThat(still[STATUS_COLUMN])
                .overridingErrorMessage("a late join must not walk the ended row back (constitution III)")
                .isEqualTo(ENDED_STATUS)
            assertThat(still[STARTED_AT_COLUMN]).isEqualTo(ended[STARTED_AT_COLUMN])
            assertNoConferenceEvent(bobStream, CONFERENCE_STARTED_EVENT, NO_EVENT_WINDOW)
        }
    }

    /**
     * SC-007 / the empty-timeout border: a `created` conference nobody
     * joined finalizes `room_lost` once the sweeper observes its room
     * gone past `conferences.empty-room-timeout` — the compensating
     * path of a LOST `room_finished` («никто не вошёл», data-model
     * §Сущность 1). The room's death is staged by the admin
     * `DeleteRoom` — exactly what the plane's own empty timeout does —
     * and `created_at` is backdated past the border (the
     * stageExpiredInvite convention: no IT waits out 60 s).
     */
    @Order(6)
    @Test
    fun `a created conference nobody joined ends room_lost past the empty-timeout`() {
        val (alice, bob) = messagingPair()
        val chatId = ensureChatOk(alice, bob.id)
        val conferenceId = createConferenceOk(alice, chatId)
        liveKitAdmin.deleteRoom(roomNameOf(conferenceId))
        val emptySince =
            clock
                .now()
                .minus(conferencesProperties.emptyRoomTimeout)
                .minusSeconds(STAGE_MARGIN_SECONDS)
        stageCreatedAt(conferenceId, emptySince)

        openUserEvents(bob).use { bobStream ->
            schedulerOf().sweep()

            val row = conferenceRow(conferenceId)
            assertThat(row[STATUS_COLUMN]).isEqualTo(ENDED_STATUS)
            assertThat(row[END_REASON_COLUMN]).isEqualTo(ROOM_LOST_REASON)
            assertEndedPayload(
                bobStream.awaitEvent(CONFERENCE_ENDED_EVENT, DELIVERY_BUDGET),
                conferenceId,
                chatId,
                ROOM_LOST_REASON,
            )
            assertThat(liveKitAdmin.listRoomNames())
                .overridingErrorMessage("the empty-timeout end must not re-create the room")
                .doesNotContain(roomNameOf(conferenceId))
        }
    }

    /**
     * Q16=B: inside the warning window the ONE `conference.warning`
     * packet — topic and the horizon payload — is ACCEPTED BY THE REAL
     * PLANE (the recording rides the production Twirp adapter; the
     * packet's RTC delivery to browsers is the plane's own business,
     * out of CI scope by livekit-integration.md §7), and only once per
     * instance; past the horizon the №48 order runs: the finalize
     * pre-record `max_duration` BEFORE `DeleteRoom`, so the live
     * connection is broken server-side and the SSE carries the reason.
     */
    @Order(7)
    @Test
    fun `max-duration warns the room once and then ends the conference the controlled way`() {
        val (alice, bob) = messagingPair()
        val chatId = ensureChatOk(alice, bob.id)
        val conferenceId = createConferenceOk(alice, chatId)
        val ticket = ticketOf(joinConferenceOk(alice, conferenceId))
        val connection = connectTicket(ticket)
        activateByWebhook(conferenceId, ticket[IDENTITY_FIELD].asText(), uniqueSourceIp())
        val plane = RecordingLiveKitAdminClient(liveKitAdmin)
        val scheduler = schedulerOf(adapter = plane)

        // INSIDE the window: the horizon lands warning-before-end − 30 s from now
        val warnedAt =
            clock
                .now()
                .minus(conferencesProperties.maxDuration)
                .plus(conferencesProperties.warningBeforeEnd)
                .minusSeconds(WINDOW_MARGIN_SECONDS)
                .truncatedTo(ChronoUnit.MICROS)
        stageStartedAt(conferenceId, warnedAt)
        scheduler.sweep()
        scheduler.sweep() // the once-per-instance fold must swallow the repeat tick

        assertThat(plane.deliveredDataPackets).hasSize(1)
        val packet = plane.deliveredDataPackets.single()
        assertThat(packet.first).isEqualTo(roomNameOf(conferenceId))
        assertThat(packet.second).isEqualTo(WARNING_TOPIC)
        assertThat(packet.third)
            .isEqualTo("""{"type":"$WARNING_TYPE","endsAt":"${warnedAt.plus(conferencesProperties.maxDuration)}"}""")
        assertThat(conferenceRow(conferenceId)[STATUS_COLUMN])
            .overridingErrorMessage("the warning must not end the conference")
            .isEqualTo(ACTIVE_STATUS)

        openUserEvents(bob).use { bobStream ->
            stageStartedAt(
                conferenceId,
                clock
                    .now()
                    .minus(conferencesProperties.maxDuration)
                    .minusSeconds(WINDOW_MARGIN_SECONDS),
            )
            scheduler.sweep()

            val row = conferenceRow(conferenceId)
            assertThat(row[STATUS_COLUMN]).isEqualTo(ENDED_STATUS)
            assertThat(row[END_REASON_COLUMN]).isEqualTo(MAX_DURATION_REASON)
            assertEndedPayload(
                bobStream.awaitEvent(CONFERENCE_ENDED_EVENT, DELIVERY_BUDGET),
                conferenceId,
                chatId,
                MAX_DURATION_REASON,
            )
            assertThat(liveKitAdmin.listRoomNames())
                .overridingErrorMessage("the controlled end must delete the room (SC-007)")
                .doesNotContain(roomNameOf(conferenceId))
            connection.awaitClosedByServer()
        }
    }

    /**
     * SC-007 / the stopped plane: while the plane is DOWN the sweeper
     * observes nothing and finalizes nothing (the documented
     * degradation — a blip must never guess `room_lost`); once the
     * plane is BACK its room set is EMPTY (the in-memory state of a
     * `--dev` node), the grace clock of the absence starts on the
     * sweeper's OWN observations and the hung call ends `room_lost`.
     * Runs LAST — it kills and restarts the shared plane, so every
     * earlier leg of the JVM keeps its live mapped port (the
     * ConferenceCreateJoinIT convention).
     */
    @Order(LAST_ORDER)
    @Test
    fun `the sweeper survives a stopped plane and ends the hung call once the plane returns`() {
        val (alice, bob) = messagingPair()
        val chatId = ensureChatOk(alice, bob.id)
        val conferenceId = createConferenceOk(alice, chatId)
        activateByWebhook(conferenceId, uniqueSourceIp())

        livekit.stop()

        // the dead plane is SKIPPED, not guessed: no observation, no finalize (research §7)
        schedulerOf().sweep()
        assertThat(conferenceRow(conferenceId)[STATUS_COLUMN]).isEqualTo(ACTIVE_STATUS)

        livekit.start()

        openUserEvents(bob).use { bobStream ->
            val returnedPlane = LiveKitServerAdminClient(returnedPlaneProperties(), objectMapper)
            val sweeper = schedulerOf(properties = returnedPlaneProperties(), adapter = returnedPlane)

            // the first observation of the absence starts the grace clock — nothing yet
            sweeper.sweep()
            assertThat(conferenceRow(conferenceId)[STATUS_COLUMN])
                .overridingErrorMessage("a single observation of the absence must not finalize before the grace")
                .isEqualTo(ACTIVE_STATUS)

            await().atMost(GRACE_BUDGET).untilAsserted {
                sweeper.sweep()
                assertThat(conferenceRow(conferenceId)[STATUS_COLUMN]).isEqualTo(ENDED_STATUS)
            }
            assertThat(conferenceRow(conferenceId)[END_REASON_COLUMN]).isEqualTo(ROOM_LOST_REASON)
            assertEndedPayload(
                bobStream.awaitEvent(CONFERENCE_ENDED_EVENT, DELIVERY_BUDGET),
                conferenceId,
                chatId,
                ROOM_LOST_REASON,
            )
        }
    }

    /**
     * T062 (FR-014, edge «чат удалён»): the owner's №30 hard delete of
     * a chat with an ACTIVE conference — every leg of the task table:
     * the running call ends `chat_deleted` INSIDE the deletion
     * transaction (the reason pre-record + the membership snapshot ride
     * it, BEFORE the ON DELETE CASCADE), the live member connections
     * are broken server-side by the post-commit `DeleteRoom`, the room
     * is gone, the FORMER members (the pre-CASCADE snapshot — the
     * CASCADE erases the roster the frame is addressed by) receive the
     * SSE `conference.ended` with `reason='chat_deleted'`, and the
     * reason is durably fixed by the winning-finalize counter — the
     * row itself dies with the chat (`conferences.chat_id` CASCADE),
     * so №45 of the dead call answers the plain `404
     * conference_not_found`.
     */
    @Order(8)
    @Test
    fun `deleting the chat ends its running conference chat_deleted and disconnects everyone`() {
        val (alice, bob) = messagingPair()
        val chatId = groupChatOf(alice, bob)
        val sourceIp = uniqueSourceIp()
        val conferenceId = createConferenceOk(alice, chatId)
        val aliceTicket = ticketOf(joinConferenceOk(alice, conferenceId))
        val aliceConnection = connectTicket(aliceTicket)
        val bobConnection = connectMember(bob, conferenceId)
        activateByWebhook(conferenceId, aliceTicket[IDENTITY_FIELD].asText(), sourceIp)
        assertThat(conferenceRow(conferenceId)[STATUS_COLUMN]).isEqualTo(ACTIVE_STATUS)
        val endedBefore = chatDeletedEnds().count()

        openUserEvents(alice).use { aliceStream ->
            openUserEvents(bob).use { bobStream ->
                deleteGroupOk(alice, chatId)

                // the media leg: the post-commit DeleteRoom breaks every
                // live connection of the dead chat's call
                aliceConnection.awaitClosedByServer()
                bobConnection.awaitClosedByServer()
                await().atMost(ROOM_GONE_BUDGET).until {
                    roomNameOf(conferenceId) !in liveKitAdmin.listRoomNames()
                }

                // the SSE leg: the frame reaches BOTH former members off
                // the pre-CASCADE roster snapshot, carrying chat_deleted
                for (stream in listOf(aliceStream, bobStream)) {
                    assertEndedPayload(
                        stream.awaitEvent(CONFERENCE_ENDED_EVENT, DELIVERY_BUDGET),
                        conferenceId,
                        chatId,
                        CHAT_DELETED_REASON,
                    )
                }

                // the reason is fixed by the winning-finalize counter — the
                // row itself CASCADEd away with the chat
                assertThat(chatDeletedEnds().count())
                    .overridingErrorMessage("the chat-deletion end must count exactly one chat_deleted finalize")
                    .isEqualTo(endedBefore + 1.0)
                assertThat(conferenceRowsOf(chatId))
                    .overridingErrorMessage("the conferences of a deleted chat die with it (V18 CASCADE)")
                    .isEmpty()
                assertThat(getConference(alice, conferenceId).statusCode)
                    .overridingErrorMessage("an old link of a deleted chat's call answers the plain 404")
                    .isEqualTo(HttpStatus.NOT_FOUND)
            }
        }
    }

    /**
     * The T056 tick driver: the DIRECTLY CONSTRUCTED scheduler of the
     * production collaborators (application-test.yml keeps the
     * scheduled bean off the IT JVM — the single-owner rule), so every
     * sweep below is exactly one deterministic tick.
     */
    private fun schedulerOf(
        properties: ConferencesProperties = conferencesProperties,
        adapter: LiveKitAdminClient = liveKitAdmin,
    ): ConferenceLifecycleScheduler =
        ConferenceLifecycleScheduler(
            conferences = conferencesRepository,
            lifecycle = lifecycleService,
            liveKit = adapter,
            properties = properties,
            metrics = conferenceMetrics,
            clock = clock,
        )

    /**
     * The properties of the RETURNED plane (the fresh mapped port of
     * the restarted container) with the grace the SC-007 leg can wait
     * out — the production collaborators stay on the context pair,
     * only the URL and the grace are the leg's own knobs.
     */
    private fun returnedPlaneProperties(): ConferencesProperties =
        conferencesProperties.copy(
            livekit =
                ConferencesProperties.LiveKit(
                    url = "http://${livekit.host}:${livekit.getMappedPort(LIVEKIT_HTTP_PORT)}",
                    apiKey = conferencesProperties.livekit.apiKey,
                    apiSecret = conferencesProperties.livekit.apiSecret,
                ),
            roomLostGrace = TEST_ROOM_LOST_GRACE,
        )

    /** Fires the plane's `participant_joined` for [identity] — the deterministic first join of the IT. */
    private fun activateByWebhook(
        conferenceId: UUID,
        identity: String,
        sourceIp: String,
    ): ResponseEntity<String> {
        val delivered = deliverWebhook(participantJoinedBody(roomNameOf(conferenceId), identity), sourceIp)
        assertThat(delivered.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(conferenceRow(conferenceId)[STATUS_COLUMN])
            .overridingErrorMessage("the staged participant_joined must activate the conference")
            .isEqualTo(ACTIVE_STATUS)
        return delivered
    }

    /** The activate seam for the legs that need no live connection: the creator's identity. */
    private fun activateByWebhook(
        conferenceId: UUID,
        sourceIp: String,
    ): ResponseEntity<String> {
        val creator = conferenceRow(conferenceId)[CREATED_BY_COLUMN]
        return activateByWebhook(conferenceId, "u:$creator:${UUID.randomUUID()}", sourceIp)
    }

    /** A signed delivery of [body] through the real intake (the receiver's own key pair of the context). */
    private fun deliverWebhook(
        body: String,
        sourceIp: String,
    ): ResponseEntity<String> =
        postWebhook(
            body,
            signedWebhookAuth(body, conferencesProperties.livekit.apiKey, conferencesProperties.livekit.apiSecret),
            sourceIp,
        )

    /** The wire body of one plane event — a FRESH `event.id` per call, so no leg is swallowed by the dedup window. */
    private fun roomEventBody(
        event: String,
        room: String,
        participant: String? = null,
    ): String {
        val id = UUID.randomUUID()
        return if (participant == null) {
            """{"event":"$event","id":"$id","room":{"name":"$room"}}"""
        } else {
            """{"event":"$event","id":"$id","room":{"name":"$room"},"participant":{"identity":"$participant"}}"""
        }
    }

    private fun participantJoinedBody(
        room: String,
        identity: String,
    ): String = roomEventBody(PARTICIPANT_JOINED_EVENT, room, identity)

    private fun roomFinishedBody(room: String): String = roomEventBody(ROOM_FINISHED_EVENT, room)

    /** The durable row of one conference — the invariant-5/6 probe surface. */
    private fun conferenceRow(conferenceId: UUID): Map<String, Any> =
        jdbcTemplate.queryForMap(CONFERENCE_ROW_SQL, conferenceId)

    /** The `webchat_conference_ended_total{end_reason='chat_deleted'}` counter — the T062 reason witness. */
    private fun chatDeletedEnds(): Counter =
        meterRegistry.counter(
            ENDED_TOTAL_METRIC,
            END_REASON_TAG,
            CHAT_DELETED_REASON,
        )

    /** The №15 history size of the chat — the Q20=A «история не изменена» probe. */
    private fun messageCountOf(chatId: UUID): Int =
        requireNotNull(jdbcTemplate.queryForObject(MESSAGE_COUNT_SQL, Int::class.java, chatId))

    /** Backdates `created_at` past the empty-timeout border (the stageExpiredInvite convention). */
    private fun stageCreatedAt(
        conferenceId: UUID,
        createdAt: Instant,
    ) {
        assertThat(
            jdbcTemplate.update(STAGE_CREATED_AT_SQL, Timestamp.from(createdAt), conferenceId),
        ).overridingErrorMessage("the created_at staging must touch the created row of <$conferenceId>")
            .isEqualTo(1)
    }

    /**
     * Stages the `started_at` of an `active` row — the max-duration
     * horizon leg of Q16=B. Truncated to MICROs: the timestamptz
     * round-trip keeps exactly that precision, so the horizon the
     * payload carries equals the staged instant verbatim.
     */
    private fun stageStartedAt(
        conferenceId: UUID,
        startedAt: Instant,
    ) {
        assertThat(
            jdbcTemplate.update(STAGE_STARTED_AT_SQL, Timestamp.from(startedAt), conferenceId),
        ).overridingErrorMessage("the started_at staging must touch the active row of <$conferenceId>")
            .isEqualTo(1)
    }

    /**
     * Consumes frames for [window] and fails the moment an `event:`
     * frame of [eventType] shows up — the «ровно один раз» halves of
     * US5-1/invariant 5 (the RealtimeSseIT pattern: a per-frame
     * timeout inside the window is the expected clean exit).
     */
    private fun assertNoConferenceEvent(
        stream: UserEventsStream,
        eventType: String,
        window: Duration,
    ) {
        val deadline = System.nanoTime() + window.toNanos()
        while (true) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return
            val frame =
                try {
                    stream.nextFrame(Duration.ofNanos(remaining))
                        ?: throw AssertionError("stream ended while asserting that no <$eventType> frame follows")
                } catch (failure: AssertionError) {
                    if (TIMEOUT_MARKER !in failure.message.orEmpty()) throw failure
                    return
                }
            assertThat(frame.event)
                .overridingErrorMessage("no further <%s> frame may follow (the once-anchor discipline)", eventType)
                .isNotEqualTo(eventType)
        }
    }

    /** realtime-conference-events.md §1: exactly conferenceId+chatId+startedAt+mode, the mode lower-case. */
    private fun assertStartedPayload(
        payload: JsonNode,
        conferenceId: UUID,
        chatId: UUID,
        mode: String,
    ) {
        assertThat(fieldNamesOf(payload))
            .containsExactlyInAnyOrder(CONFERENCE_ID_FIELD, CHAT_ID_FIELD, STARTED_AT_FIELD, MODE_FIELD)
        assertThat(payload[CONFERENCE_ID_FIELD].asText()).isEqualTo(conferenceId.toString())
        assertThat(payload[CHAT_ID_FIELD].asText()).isEqualTo(chatId.toString())
        assertThat(Instant.parse(payload[STARTED_AT_FIELD].asText()))
            .overridingErrorMessage("startedAt must be an ISO-8601 instant")
            .isNotNull
        assertThat(payload[MODE_FIELD].asText()).isEqualTo(mode)
    }

    /** realtime-conference-events.md §2: exactly conferenceId+chatId+endedAt+reason, the reason lower-case. */
    private fun assertEndedPayload(
        payload: JsonNode,
        conferenceId: UUID,
        chatId: UUID,
        reason: String,
    ) {
        assertThat(fieldNamesOf(payload))
            .containsExactlyInAnyOrder(CONFERENCE_ID_FIELD, CHAT_ID_FIELD, ENDED_AT_FIELD, REASON_FIELD)
        assertThat(payload[CONFERENCE_ID_FIELD].asText()).isEqualTo(conferenceId.toString())
        assertThat(payload[CHAT_ID_FIELD].asText()).isEqualTo(chatId.toString())
        assertThat(Instant.parse(payload[ENDED_AT_FIELD].asText()))
            .overridingErrorMessage("endedAt must be an ISO-8601 instant")
            .isNotNull
        assertThat(payload[REASON_FIELD].asText()).isEqualTo(reason)
    }

    private fun fieldNamesOf(node: JsonNode): List<String> = node.fieldNames().asSequence().toList()

    /**
     * The recording seam of the Q16=B warning leg: delegates EVERY
     * port call to the PRODUCTION adapter (the packet truly leaves
     * into the real plane) and remembers the data packets that were
     * ACCEPTED — the RTC delivery to browsers is the plane's business,
     * out of CI scope by livekit-integration.md §7, so the IT asserts
     * at the port boundary the unit test mocks.
     */
    private class RecordingLiveKitAdminClient(
        private val delegate: LiveKitAdminClient,
    ) : LiveKitAdminClient by delegate {
        val deliveredDataPackets = CopyOnWriteArrayList<Triple<String, String, String>>()

        override fun sendData(
            room: String,
            topic: String,
            payload: String,
        ) {
            delegate.sendData(room, topic, payload)
            deliveredDataPackets += Triple(room, topic, payload)
        }
    }

    private companion object {
        /**
         * The fixture source IPs of the lifecycle IT — the conferences
         * suite's OWN 203.0.117.x block (the guest IT owns .116, the
         * messaging fixtures .114, the generic rotator .113 — the
         * bucket-isolation map of ConferenceGuestAccessIT), one FRESH
         * address per call so no leg of the class ever shares a
         * `rl:ip:*` bucket with another (webhook 60/1m, №54 30/1m,
         * №55 10/1m).
         */
        val sourceIpCounter = AtomicLong()

        fun uniqueSourceIp(): String = "203.0.117.${1L + sourceIpCounter.incrementAndGet() % SOURCE_IP_LAST_OCTET_SPAN}"

        const val SOURCE_IP_LAST_OCTET_SPAN = 254L

        /** The SC-006 budget itself — the IT asserts the contract number, not a CI-tolerant multiple of it. */
        val SC_006: Duration = Duration.ofSeconds(2)

        /** The window every «no further event» leg waits before declaring the once-anchor held. */
        val NO_EVENT_WINDOW: Duration = Duration.ofSeconds(2)

        /** The catch budget of an expected frame (the SC-006 number is asserted on the measured elapsed). */
        val DELIVERY_BUDGET: Duration = Duration.ofSeconds(5)

        /** The lowered grace of the returned-plane leg + the convergence budget of its sweep loop. */
        val TEST_ROOM_LOST_GRACE: Duration = Duration.ofSeconds(2)

        val GRACE_BUDGET: Duration = Duration.ofSeconds(15)

        const val LAST_ORDER = 1000

        const val WINDOW_MARGIN_SECONDS = 30L

        const val STAGE_MARGIN_SECONDS = 1L

        const val CONFERENCE_STARTED_EVENT = "conference.started"

        const val CONFERENCE_ENDED_EVENT = "conference.ended"

        const val ROOM_STARTED_EVENT = "room_started"

        const val PARTICIPANT_JOINED_EVENT = "participant_joined"

        const val ROOM_FINISHED_EVENT = "room_finished"

        /** livekit-integration.md §4: the data-packet topic of the max-duration warning (T056). */
        const val WARNING_TOPIC = "conference.warning"

        const val WARNING_TYPE = "conference.warning"

        const val CREATED_STATUS = "created"

        const val ACTIVE_STATUS = "active"

        const val ENDED_STATUS = "ended"

        const val LAST_LEFT_REASON = "last_left"

        const val ROOM_LOST_REASON = "room_lost"

        const val MAX_DURATION_REASON = "max_duration"

        /** data-model 009 §Сущность 1: the chat-deletion end reason of the T062 listener. */
        const val CHAT_DELETED_REASON = "chat_deleted"

        /** The metric name/tag of the winning-finalize counter (ConferenceMetrics). */
        const val ENDED_TOTAL_METRIC = "webchat_conference_ended_total"

        const val END_REASON_TAG = "end_reason"

        /** The room-gone convergence budget of the T062 media leg (the DeleteRoom is already post-commit). */
        val ROOM_GONE_BUDGET: Duration = Duration.ofSeconds(5)

        /** The №54 `reason` label of an ended conference (api-contract.md 009 §3). */
        const val ENDED_REASON = "ended"

        const val CONFERENCE_ENDED_CODE = "conference_ended"

        const val STATUS_FIELD = "status"

        const val CONFERENCE_ID_FIELD = "conferenceId"

        const val CHAT_ID_FIELD = "chatId"

        const val STARTED_AT_FIELD = "startedAt"

        const val ENDED_AT_FIELD = "endedAt"

        const val STATUS_COLUMN = "status"

        const val STARTED_AT_COLUMN = "started_at"

        const val ENDED_AT_COLUMN = "ended_at"

        const val END_REASON_COLUMN = "end_reason"

        const val CREATED_BY_COLUMN = "created_by"

        const val TIMEOUT_MARKER = "timed out"

        val CONFERENCE_ROW_SQL =
            """
            SELECT * FROM conferences WHERE id = ?
            """.trimIndent()

        val MESSAGE_COUNT_SQL =
            """
            SELECT count(*) FROM messages WHERE chat_id = ?
            """.trimIndent()

        val STAGE_CREATED_AT_SQL =
            """
            UPDATE conferences SET created_at = ? WHERE id = ? AND status = 'created'
            """.trimIndent()

        val STAGE_STARTED_AT_SQL =
            """
            UPDATE conferences SET started_at = ? WHERE id = ? AND status = 'active'
            """.trimIndent()
    }
}
