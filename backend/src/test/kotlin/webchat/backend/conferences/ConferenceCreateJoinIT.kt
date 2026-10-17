package webchat.backend.conferences

import com.fasterxml.jackson.databind.JsonNode
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jwt.SignedJWT
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * T021 (tasks.md Phase 3, US1) — the story IT of the №44–№47 surface
 * against the REAL stack: Testcontainers PG 17 + Redis 7 (the
 * [webchat.backend.AbstractIntegrationTest] base) + the LiveKit
 * container of [ConferencesTestSupport], with actual signal
 * connections occupying real room slots. One pinned leg per rule of
 * the task table:
 *
 *  * **invariant 1** — two CONCURRENT №44 presses on one chat resolve
 *    into a single conference (`ux_conferences_chat_active`): one
 *    `201` + one `200` of the SAME row, exactly one `kind='system'`
 *    invite widget in the №15 history (FR-002) and one eager room
 *    `c:{id}`;
 *  * **invariant 2 / SC-002** — a non-member's №47 dies on the
 *    every-join membership gate (`403 conference_forbidden`, FR-003)
 *    BEFORE any principal row exists; №45 folds the stranger into the
 *    uniform `404 conference_not_found`;
 *  * **invariant 3 / Q5=A** — restrictions persisted on the principal
 *    row (staged into the №49 columns — the SET endpoint itself is
 *    T036) reproduce verbatim in the grants of the NEXT №47 mint, and
 *    the «право слова» blocks the whole publish side (Q4=A);
 *  * **the FR-003 edge (T020)** — a member excluded (№32) or leaving
 *    (№33) the chat mid-call has his live connections broken
 *    server-side (`RemoveParticipant` by the `u:{userId}:` prefix)
 *    while OTHER members stay connected, and his next №47 answers the
 *    same `403 conference_forbidden` as any stranger's;
 *  * **FR-015 / the two-tabs edge** — a second tab of one member is a
 *    distinct connection identity occupying its OWN slot: two tabs +
 *    one peer exhaust the lowered quota, and the next member's №47
 *    answers `409 conference_full` with no principal row written;
 *  * **FR-001** — an E2EE chat refuses the create (`409
 *    conference_e2ee_unsupported`) with NO room and NO widget;
 *  * **the media-plane edge** — with the LiveKit container STOPPED,
 *    №44 answers `503 conference_media_unavailable` and the create
 *    transaction is rolled back entirely (no row, no widget).
 *
 * The last leg is why the class is order-annotated: it kills the
 * shared plane, so it must run after everything else.
 */
@TestMethodOrder(OrderAnnotation::class)
@Suppress("TooManyFunctions") // one method per rule of the T021 task table
class ConferenceCreateJoinIT : ConferencesTestSupport() {
    private val openConnections = mutableListOf<RoomConnection>()

    @AfterEach
    fun closeFixtureConnections() {
        openConnections.forEach(RoomConnection::close)
        openConnections.clear()
    }

    /** Invariant 1 + FR-002: the «double press» resolves into ONE conference, ONE widget, ONE room. */
    @Order(1)
    @Test
    fun `concurrent creates resolve into one conference with a single invite widget`() {
        val (alice, bob) = messagingPair()
        val chatId = ensureChatOk(alice, bob.id)
        val barrier = CyclicBarrier(PRESS_COUNT)
        val executor = Executors.newFixedThreadPool(PRESS_COUNT)
        try {
            val presses =
                listOf(alice to VIDEO_MODE, bob to AUDIO_MODE)
                    .map { (user, mode) ->
                        executor.submit<Pair<ResponseEntity<String>, String>> {
                            barrier.await()
                            createConference(user, chatId, mode) to mode
                        }
                    }.map { it.get(PRESS_TIMEOUT_SECONDS, TimeUnit.SECONDS) }

            val statuses = presses.map { (response, _) -> response.statusCode }.toSet()
            assertThat(statuses)
                .overridingErrorMessage(
                    "the concurrent №44 pair must answer exactly one 201 and one 200 (FR-001 idempotence), got <%s>",
                    statuses,
                ).containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.OK)

            val winner =
                presses
                    .first { (response, _) -> response.statusCode == HttpStatus.CREATED }
            val (winnerResponse, winnerMode) = winner
            val loserResponse = presses.first { (response, _) -> response.statusCode == HttpStatus.OK }.first
            val winnerView = conferenceViewOf(winnerResponse)
            val conferenceId = UUID.fromString(winnerView[ID_FIELD].asText())
            assertThat(conferenceViewOf(loserResponse)[ID_FIELD].asText())
                .overridingErrorMessage("the competing press must resolve to the SAME conference row")
                .isEqualTo(conferenceId.toString())

            val rows = conferenceRowsOf(chatId)
            assertThat(rows).hasSize(1)
            assertThat(rows.single()[STATUS_FIELD]).isEqualTo(CREATED_STATUS)
            assertThat(rows.single()[MAX_PARTICIPANTS_FIELD]).isEqualTo(QUOTA)

            assertSingleInviteWidget(chatId, conferenceId, winnerMode)
            assertInviteCardInHistory(alice, chatId, conferenceId)

            assertThat(liveKitAdmin.listRoomNames())
                .overridingErrorMessage("the eager №44 room c:{id} must exist after the winning press (T017)")
                .contains(roomNameOf(conferenceId))

            val view = conferenceViewOf(getConference(bob, conferenceId))
            assertThat(view[MODE_FIELD].asText())
                .overridingErrorMessage("№45 answers the mode of THE CONFERENCE (the winner's widget)")
                .isEqualTo(winnerMode)
            assertThat(view[STATUS_FIELD].asText()).isEqualTo(CREATED_STATUS)
            assertThat(view[ACTIVE_PARTICIPANTS_FIELD].asInt()).isZero
        } finally {
            executor.shutdownNow()
        }
    }

    /** FR-002: exactly one durable `kind='system'` widget row rides the winning create transaction. */
    private fun assertSingleInviteWidget(
        chatId: UUID,
        conferenceId: UUID,
        winnerMode: String,
    ) {
        val widgets = systemMessageRowsOf(chatId)
        assertThat(widgets)
            .overridingErrorMessage("exactly ONE invite widget may ride the winning create transaction (FR-002)")
            .hasSize(1)
        val widget = widgets.single()
        assertThat(widget[KIND_FIELD]).isEqualTo(SYSTEM_KIND)
        assertThat(widget[SENDER_ID_FIELD])
            .overridingErrorMessage("a system widget carries no sender (V18 shape CHECK)")
            .isNull()
        val payload = widgetPayloadOf(widget)
        assertThat(payload[TYPE_FIELD].asText()).isEqualTo(CONFERENCE_INVITE_TYPE)
        assertThat(payload[CONFERENCE_ID_FIELD].asText()).isEqualTo(conferenceId.toString())
        assertThat(payload[MODE_FIELD].asText()).isEqualTo(winnerMode)
    }

    /** FR-002 through the contract surface: №15 shows the card with kind='system', no sender and the widget payload. */
    private fun assertInviteCardInHistory(
        alice: MessagingUser,
        chatId: UUID,
        conferenceId: UUID,
    ) {
        val history = listMessages(alice, chatId)
        assertThat(history.statusCode).isEqualTo(HttpStatus.OK)
        val inviteFrame =
            objectMapper
                .readTree(history.body)[MESSAGES_FIELD]
                ?.singleOrNull { it[KIND_FIELD].asText() == SYSTEM_KIND }
        assertThat(inviteFrame)
            .overridingErrorMessage("the №15 history must show the invite card with kind='system'")
            .isNotNull
        assertThat(inviteFrame!![SENDER_ID_JSON_FIELD].isNull).isTrue
        assertThat(inviteFrame[PAYLOAD_FIELD]?.get(TYPE_FIELD)?.asText()).isEqualTo(CONFERENCE_INVITE_TYPE)
        assertThat(inviteFrame[PAYLOAD_FIELD]?.get(CONFERENCE_ID_FIELD)?.asText())
            .isEqualTo(conferenceId.toString())
    }

    /** Invariant 2 / SC-002: the non-member's №47 dies on the membership gate before any principal row. */
    @Order(2)
    @Test
    fun `a stranger is refused 403 before any participant row`() {
        val (alice, bob) = messagingPair()
        val chatId = ensureChatOk(alice, bob.id)
        val conferenceId = createConferenceOk(alice, chatId)
        val carol = strangerUser()

        val refused = joinConference(carol, conferenceId)

        assertThat(refused.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(problemCode(refused)).isEqualTo(CONFERENCE_FORBIDDEN_CODE)

        val rows = participantRowsOf(conferenceId)
        assertThat(rows)
            .overridingErrorMessage(
                "the only principal row is the creator's moderator row — a refused join writes nothing (SC-002)",
            ).hasSize(1)
        assertThat(rows.single()[USER_ID_FIELD]).isEqualTo(alice.id)
        assertThat(rows.single()[ROLE_FIELD]).isEqualTo(MODERATOR_ROLE)

        val folded = getConference(carol, conferenceId)
        assertThat(folded.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(problemCode(folded)).isEqualTo(CONFERENCE_NOT_FOUND_CODE)
    }

    /** Invariant 3 / Q5=A / Q4=A: persisted restrictions reproduce in the grants of the next mint. */
    @Order(3)
    @Test
    fun `ticket grants reproduce the persisted restrictions`() {
        val (alice, bob) = messagingPair()
        val chatId = ensureChatOk(alice, bob.id)
        val conferenceId = createConferenceOk(alice, chatId)

        val firstJoin = joinConference(bob, conferenceId)
        assertThat(firstJoin.statusCode).isEqualTo(HttpStatus.OK)
        val firstTicket = ticketOf(firstJoin)
        assertThat(firstTicket[IDENTITY_FIELD].asText()).matches("u:${bob.id}:$UUID_PATTERN")
        assertThat(firstTicket[NAME_FIELD].asText()).isEqualTo(bob.username)
        assertThat(firstTicket[WS_URL_FIELD].asText()).startsWith(LOCALHOST_PREFIX)

        stageSourceRestrictions(conferenceId, bob.id, micRestricted = true, screenRestricted = true)

        val restricted = videoGrantsOf(joinConferenceOk(bob, conferenceId), conferenceId)
        assertThat(restricted[CAN_PUBLISH_FIELD]).isEqualTo(true)
        assertThat(restricted[CAN_PUBLISH_SOURCES_FIELD])
            .overridingErrorMessage("mic+screen restrictions drop microphone and both screen sources (Q22=A)")
            .isEqualTo(listOf(CAMERA_SOURCE))

        stageSpeechRestriction(conferenceId, bob.id)

        val silenced = videoGrantsOf(joinConferenceOk(bob, conferenceId), conferenceId)
        assertThat(silenced[CAN_PUBLISH_FIELD])
            .overridingErrorMessage("the «право слова» blocks ANY publication (Q4=A)")
            .isEqualTo(false)
        assertThat(silenced[CAN_PUBLISH_SOURCES_FIELD]).isEqualTo(emptyList<String>())
    }

    /**
     * The FR-003 edge (T020): №32 exclusion and №33 voluntary exit both break
     * the member's live connections server-side and close his re-join path.
     */
    @Order(4)
    @Test
    fun `chat removal during the call breaks the removed member's connections and blocks re-join`() {
        val owner = messagingUser("owner")
        val bob = messagingUser("bob")
        val carol = messagingUser("carol")
        val chatId = groupChatOf(owner, bob, carol)
        val conferenceId = createConferenceOk(owner, chatId)

        val bobConnection = connectMember(bob, conferenceId)
        val carolConnection = connectMember(carol, conferenceId)
        val room = roomNameOf(conferenceId)
        awaitRoomParticipants(room) { identities ->
            identities.size == CONNECTED_TWO &&
                identities.any { it.startsWith(memberPrefixOf(bob.id)) } &&
                identities.any { it.startsWith(memberPrefixOf(carol.id)) }
        }

        kickGroupMember(owner, chatId, bob.id)

        awaitRoomParticipants(room) { identities ->
            identities.none { it.startsWith(memberPrefixOf(bob.id)) } &&
                identities.any { it.startsWith(memberPrefixOf(carol.id)) }
        }
        bobConnection.awaitClosedByServer()
        val bobRejoin = joinConference(bob, conferenceId)
        assertThat(bobRejoin.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(problemCode(bobRejoin)).isEqualTo(CONFERENCE_FORBIDDEN_CODE)

        leaveGroup(carol, chatId)

        awaitRoomParticipants(room) { identities -> identities.none { it.startsWith(memberPrefixOf(carol.id)) } }
        carolConnection.awaitClosedByServer()
        val carolRejoin = joinConference(carol, conferenceId)
        assertThat(carolRejoin.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(problemCode(carolRejoin)).isEqualTo(CONFERENCE_FORBIDDEN_CODE)
    }

    /** FR-015 / the two-tabs edge: a second tab takes a real slot; the exhausted quota refuses the next member. */
    @Order(5)
    @Test
    fun `two tabs of one member occupy two slots and exhaust the quota`() {
        val owner = messagingUser("owner")
        val bob = messagingUser("bob")
        val carol = messagingUser("carol")
        val chatId = groupChatOf(owner, bob, carol)
        val conferenceId = createConferenceOk(owner, chatId)

        val firstTab = ticketOf(joinConferenceOk(owner, conferenceId))
        val secondTab = ticketOf(joinConferenceOk(owner, conferenceId))
        assertThat(firstTab[IDENTITY_FIELD].asText())
            .overridingErrorMessage("every mint is a fresh per-connection identity (the two-tabs edge, research §4)")
            .isNotEqualTo(secondTab[IDENTITY_FIELD].asText())
        connectTicket(firstTab)
        connectTicket(secondTab)
        connectTicket(ticketOf(joinConferenceOk(bob, conferenceId)))

        val room = roomNameOf(conferenceId)
        awaitRoomParticipants(room) { it.size == QUOTA }

        val view = conferenceViewOf(getConference(owner, conferenceId))
        assertThat(view[ACTIVE_PARTICIPANTS_FIELD].asInt())
            .overridingErrorMessage("№45 counts the media-plane truth: two tabs of one member ARE two connections")
            .isEqualTo(QUOTA)

        val refused = joinConference(carol, conferenceId)
        assertThat(refused.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(problemCode(refused)).isEqualTo(CONFERENCE_FULL_CODE)
        assertThat(participantRowsOf(conferenceId).map { it[USER_ID_FIELD] })
            .overridingErrorMessage("a quota-refused join writes no principal row")
            .doesNotContain(carol.id)
    }

    /** FR-001: the E2EE boundary — no conference, no room, no widget. */
    @Order(6)
    @Test
    fun `an e2ee chat refuses the create without any side effect`() {
        val (alice, bob) = messagingPair()
        val chatId = ensureChatOk(alice, bob.id)
        stageE2eeChat(chatId)
        val roomsBefore = liveKitAdmin.listRoomNames()

        val refused = createConference(alice, chatId, VIDEO_MODE)

        assertThat(refused.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(problemCode(refused)).isEqualTo(E2EE_UNSUPPORTED_CODE)
        assertThat(liveKitAdmin.listRoomNames() - roomsBefore)
            .overridingErrorMessage("the refusal happened before any media-plane call — no room may exist")
            .isEmpty()
        assertThat(conferenceRowsOf(chatId)).isEmpty()
        assertThat(systemMessageRowsOf(chatId)).isEmpty()
    }

    /**
     * The media-plane edge: a stopped plane answers 503 with the whole
     * create rolled back. Runs LAST (kills the plane).
     */
    @Order(LAST_ORDER)
    @Test
    fun `a stopped media plane answers 503 and writes nothing`() {
        val (alice, bob) = messagingPair()
        val chatId = ensureChatOk(alice, bob.id)

        livekit.stop()

        val refused = createConference(alice, chatId, VIDEO_MODE)
        assertThat(refused.statusCode).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
        assertThat(problemCode(refused)).isEqualTo(MEDIA_UNAVAILABLE_CODE)
        assertThat(conferenceRowsOf(chatId))
            .overridingErrorMessage("the pre-flight refusal leaves no conference row (research §7)")
            .isEmpty()
        assertThat(systemMessageRowsOf(chatId))
            .overridingErrorMessage("the rolled-back transaction posts no invite widget")
            .isEmpty()
    }

    /** №44 happy-path fixture → the conferenceId of the created row. */
    private fun createConferenceOk(
        user: MessagingUser,
        chatId: UUID,
        mode: String = VIDEO_MODE,
    ): UUID {
        val response = createConference(user, chatId, mode)
        assertThat(response.statusCode)
            .overridingErrorMessage("fixture №44 must answer 201, got <%s>: %s", response.statusCode, response.body)
            .isEqualTo(HttpStatus.CREATED)
        return UUID.fromString(conferenceViewOf(response)[ID_FIELD].asText())
    }

    /** №47 happy-path fixture → the parsed `JoinTicket`. */
    private fun joinConferenceOk(
        user: MessagingUser,
        conferenceId: UUID,
    ): ResponseEntity<String> {
        val response = joinConference(user, conferenceId)
        assertThat(response.statusCode)
            .overridingErrorMessage(
                "fixture №47 of member <${user.username}> must answer 200, got <%s>: %s",
                response.statusCode,
                response.body,
            ).isEqualTo(HttpStatus.OK)
        return response
    }

    /** №47 + a joined signal connection of the minted ticket — the media-plane actor of the story legs. */
    private fun connectMember(
        user: MessagingUser,
        conferenceId: UUID,
    ): RoomConnection = connectTicket(ticketOf(joinConferenceOk(user, conferenceId)))

    private fun connectTicket(ticket: JsonNode): RoomConnection {
        val connection =
            RoomConnection(
                wsUrl = ticket[WS_URL_FIELD].asText(),
                token = ticket[TOKEN_FIELD].asText(),
            )
        openConnections.add(connection)
        connection.awaitJoined()
        return connection
    }

    /**
     * The decoded `video` grant map of a minted ticket (the §3 claim
     * table). The ticket's SIGNATURE is proven the strong way — by the
     * media plane itself accepting it in the connection legs (nimbus
     * [MACVerifier] refuses the short `--dev` placeholder secret by
     * design, KeyLengthException) — so this helper only pins the
     * claims: issuer/subject/name/metadata and the grant table.
     */
    @Suppress("UNCHECKED_CAST")
    private fun videoGrantsOf(
        response: ResponseEntity<String>,
        conferenceId: UUID,
    ): Map<String, Any> {
        val ticket = ticketOf(response)
        val jwt = SignedJWT.parse(ticket[TOKEN_FIELD].asText())
        assertThat(jwt.header.algorithm).isEqualTo(JWSAlgorithm.HS256)
        val claims = jwt.jwtClaimsSet
        assertThat(claims.issuer).isEqualTo(LIVEKIT_API_KEY)
        assertThat(claims.subject).isEqualTo(ticket[IDENTITY_FIELD].asText())
        assertThat(claims.getStringClaim(NAME_FIELD)).isEqualTo(ticket[NAME_FIELD].asText())
        assertThat(claims.getStringClaim(METADATA_FIELD))
            .overridingErrorMessage("the member metadata rides the persisted role (T018)")
            .isEqualTo("""{"role":"participant","guest":false}""")
        val video = claims.getClaim(VIDEO_FIELD) as Map<String, Any>
        assertThat(video[ROOM_JOIN_FIELD]).isEqualTo(true)
        assertThat(video[ROOM_FIELD]).isEqualTo(roomNameOf(conferenceId))
        assertThat(video[CAN_SUBSCRIBE_FIELD]).isEqualTo(true)
        assertThat(video[CAN_PUBLISH_DATA_FIELD]).isEqualTo(false)
        return video
    }

    private fun roomNameOf(conferenceId: UUID): String = "$ROOM_PREFIX$conferenceId"

    private fun memberPrefixOf(userId: UUID): String = "u:$userId:"

    private companion object {
        const val PRESS_TIMEOUT_SECONDS = 30L

        const val PRESS_COUNT = 2

        const val CONNECTED_TWO = 2

        const val LAST_ORDER = 1000

        const val LIVEKIT_API_KEY = "devkey"

        const val VIDEO_MODE = "video"

        const val AUDIO_MODE = "audio"

        const val CREATED_STATUS = "created"

        const val SYSTEM_KIND = "system"

        const val MODERATOR_ROLE = "moderator"

        const val CONFERENCE_INVITE_TYPE = "conference_invite"

        const val UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"

        const val CONFERENCE_FORBIDDEN_CODE = "conference_forbidden"

        const val CONFERENCE_NOT_FOUND_CODE = "conference_not_found"

        const val CONFERENCE_FULL_CODE = "conference_full"

        const val E2EE_UNSUPPORTED_CODE = "conference_e2ee_unsupported"

        const val MEDIA_UNAVAILABLE_CODE = "conference_media_unavailable"

        const val ID_FIELD = "id"

        const val STATUS_FIELD = "status"

        const val MAX_PARTICIPANTS_FIELD = "max_participants"

        const val ACTIVE_PARTICIPANTS_FIELD = "activeParticipants"

        const val KIND_FIELD = "kind"

        const val SENDER_ID_FIELD = "sender_id"

        const val SENDER_ID_JSON_FIELD = "senderId"

        const val PAYLOAD_FIELD = "payload"

        const val CONFERENCE_ID_FIELD = "conferenceId"

        const val MODE_FIELD = "mode"

        const val TYPE_FIELD = "type"

        const val MESSAGES_FIELD = "messages"

        const val USER_ID_FIELD = "user_id"

        const val ROLE_FIELD = "role"

        const val IDENTITY_FIELD = "identity"

        const val NAME_FIELD = "name"

        const val WS_URL_FIELD = "wsUrl"

        const val TOKEN_FIELD = "token"

        const val METADATA_FIELD = "metadata"

        const val VIDEO_FIELD = "video"

        const val ROOM_JOIN_FIELD = "roomJoin"

        const val ROOM_FIELD = "room"

        const val CAN_SUBSCRIBE_FIELD = "canSubscribe"

        const val CAN_PUBLISH_FIELD = "canPublish"

        const val CAN_PUBLISH_SOURCES_FIELD = "canPublishSources"

        const val CAN_PUBLISH_DATA_FIELD = "canPublishData"

        const val CAMERA_SOURCE = "camera"

        const val ROOM_PREFIX = "c:"

        const val LOCALHOST_PREFIX = "http://localhost"
    }
}
