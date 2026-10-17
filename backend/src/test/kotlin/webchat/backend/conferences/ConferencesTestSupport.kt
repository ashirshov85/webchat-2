package webchat.backend.conferences

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import webchat.backend.chats.MessagingTestSupport
import webchat.backend.conferences.domain.model.ParticipantRestrictions
import webchat.backend.conferences.domain.port.LiveKitAdminClient
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * T021 (tasks.md Phase 3, US1): the shared fixtures of the conference
 * story ITs — [ConferenceCreateJoinIT] and the later US3/US4/US5 sets —
 * on top of [MessagingTestSupport] (registered users, dialogs, №15
 * history reads, unique fixture source IPs).
 *
 * The additions over the messaging base:
 *
 *  * a real **LiveKit media plane** — one `livekit/livekit-server`
 *    GenericContainer of the `deploy/local/docker-compose.yml` shape
 *    (research §13, livekit-integration.md §7: `--dev`-grade single
 *    node, the public `devkey`/`secret` pair, `enable_remote_unmute`),
 *    wired into `conferences.livekit.*` through a
 *    [DynamicPropertySource] so the whole №44–№47 surface runs against
 *    the REAL Twirp plane (CreateRoom/rooms, quota truth of
 *    ListParticipants) — no port fake can pin those contracts;
 *  * **`max-participants=3`** — the lowered quota of research §13, so
 *    the FR-015 legs (conference_full, the two-tabs edge) fit into a
 *    few signal connections;
 *  * a **signal-only room connection** [RoomConnection]: the IT answer
 *    to livekit-integration.md §7 «медиа из CI не гоняем» — a raw
 *    WebSocket to `/rtc` with a REAL №47-minted ticket. LiveKit
 *    registers a participant once the signal handshake completes
 *    (verified against v1.13.10: a participant without any WebRTC
 *    offer still shows up in `ListParticipants` as `JOINED`, occupies
 *    a quota slot and is closed by `RemoveParticipant`), which is
 *    exactly the media-plane truth the control plane consumes;
 *  * the HTTP legs of №44–№47 and the №21/№27/№32/№33 group fixtures
 *    the FR-003 edge rides on, plus the JdbcTemplate probes of the
 *    durable invariants (the conference row, the `kind='system'`
 *    invite widget, the principal rows).
 */
@Suppress("TooManyFunctions") // T021: fixture library — one helper per leg of the story ITs
abstract class ConferencesTestSupport : MessagingTestSupport() {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    protected lateinit var objectMapper: ObjectMapper

    @Autowired
    protected lateinit var jdbcTemplate: JdbcTemplate

    /** The production Twirp adapter — the room/roster probe surface of the story assertions. */
    @Autowired
    protected lateinit var liveKitAdmin: LiveKitAdminClient

    /** Contract №44 `POST /api/v1/chats/{chatId}/conferences` — raw response. */
    protected fun createConference(
        user: MessagingUser,
        chatId: UUID,
        mode: String,
    ): ResponseEntity<String> {
        val headers =
            HttpHeaders().apply {
                contentType = MediaType.APPLICATION_JSON
                setBearerAuth(user.accessToken)
            }
        return restTemplate.postForEntity(
            "/api/v1/chats/$chatId/conferences",
            HttpEntity(mapOf(MODE_FIELD to mode), headers),
            String::class.java,
        )
    }

    /** Contract №45 `GET /api/v1/conferences/{conferenceId}` — raw response. */
    protected fun getConference(
        user: MessagingUser,
        conferenceId: UUID,
    ): ResponseEntity<String> = exchangeAuthorized(user, "/api/v1/conferences/$conferenceId")

    /** Contract №47 `POST /api/v1/conferences/{conferenceId}/join` — raw response. */
    protected fun joinConference(
        user: MessagingUser,
        conferenceId: UUID,
    ): ResponseEntity<String> {
        val headers =
            HttpHeaders().apply {
                contentType = MediaType.APPLICATION_JSON
                setBearerAuth(user.accessToken)
            }
        return restTemplate.postForEntity(
            "/api/v1/conferences/$conferenceId/join",
            HttpEntity(Unit, headers),
            String::class.java,
        )
    }

    /** Contract №21 `POST /api/v1/contacts` — fixture contact of the №27/№31 gates. */
    protected fun addContact(
        user: MessagingUser,
        userId: UUID,
    ) {
        val response = postJsonAuthorized(user, CONTACTS_PATH, mapOf(USER_ID_FIELD to userId.toString()))
        assertThat(response.statusCode.is2xxSuccessful)
            .overridingErrorMessage(
                "fixture contact №21 of <%s> → <%s> must succeed, got <%s>: %s",
                user.username,
                userId,
                response.statusCode,
                response.body,
            ).isTrue
    }

    /**
     * Contract №27 `POST /api/v1/groups` — a fresh GROUP chat (the only
     * chat kind №32/№33 exist on) with [owner] and every [member] in the
     * ACTIVE roster: the FR-003-edge fixture of the story ITs.
     */
    protected fun groupChatOf(
        owner: MessagingUser,
        vararg member: MessagingUser,
    ): UUID {
        member.forEach { addContact(owner, it.id) }
        val response =
            postJsonAuthorized(
                owner,
                GROUPS_PATH,
                buildMap {
                    put(TITLE_FIELD, "t021-group-${UUID.randomUUID().toString().substring(0, TITLE_SUFFIX_LENGTH)}")
                    put(MEMBER_USER_IDS_FIELD, member.map { it.id.toString() })
                },
            )
        assertThat(response.statusCode)
            .overridingErrorMessage(
                "№27 group fixture must answer 201, got <%s>: %s",
                response.statusCode,
                response.body,
            ).isEqualTo(HttpStatus.CREATED)
        return UUID.fromString(objectMapper.readTree(response.body)[CHAT_ID_FIELD].asText())
    }

    /** Contract №32 `DELETE /api/v1/groups/{chatId}/members/{userId}` — the exclusion leg of the FR-003 edge. */
    protected fun kickGroupMember(
        owner: MessagingUser,
        chatId: UUID,
        userId: UUID,
    ) {
        val response = exchangeAuthorized(owner, "$GROUPS_PATH/$chatId/members/$userId", HttpMethod.DELETE)
        assertThat(response.statusCode)
            .overridingErrorMessage(
                "№32 kick of <$userId> must answer 204, got <%s>: %s",
                response.statusCode,
                response.body,
            ).isEqualTo(HttpStatus.NO_CONTENT)
    }

    /** Contract №33 `DELETE /api/v1/groups/{chatId}/membership` — the voluntary-exit leg of the FR-003 edge. */
    protected fun leaveGroup(
        user: MessagingUser,
        chatId: UUID,
    ) {
        val response = exchangeAuthorized(user, "$GROUPS_PATH/$chatId/membership", HttpMethod.DELETE)
        assertThat(response.statusCode)
            .overridingErrorMessage(
                "№33 leave of <${user.username}> must answer 204, got <%s>: %s",
                response.statusCode,
                response.body,
            ).isEqualTo(HttpStatus.NO_CONTENT)
    }

    /**
     * The RFC 9457 problem code of a refused №44–№47 answer — the
     * `errors: {conference: [<code>]}` shape of api-contract.md 009 §1
     * (the ChatAccessIT convention over the conferences field).
     */
    protected fun problemCode(response: ResponseEntity<String>): String? {
        assertThat(response.headers.contentType?.toString())
            .overridingErrorMessage("a refused conferences answer must be RFC 9457 application/problem+json")
            .contains(PROBLEM_JSON_MEDIA_TYPE)
        return objectMapper
            .readTree(response.body)
            .get(ERRORS_FIELD)
            ?.get(CONFERENCE_FIELD)
            ?.get(FIRST_ELEMENT)
            ?.asText()
    }

    /** The parsed success body of №44/№45 (the `ConferenceView` of api-contract.md 009 §4). */
    protected fun conferenceViewOf(response: ResponseEntity<String>): JsonNode = objectMapper.readTree(response.body)

    /** The parsed success body of №47 (the `JoinTicket` of api-contract.md 009 §4). */
    protected fun ticketOf(response: ResponseEntity<String>): JsonNode = objectMapper.readTree(response.body)

    /** The durable conference rows of [chatId] — the «nothing was written» probe of the refusal legs. */
    protected fun conferenceRowsOf(chatId: UUID): List<Map<String, Any>> =
        jdbcTemplate.queryForList(CONFERENCES_OF_CHAT_SQL, chatId)

    /**
     * The `kind='system'` widget rows of [chatId] (the T016 invite
     * card, FR-002) — `payload` is cast to `payload_text` so the jsonb
     * column arrives as a parseable string, not a driver PGobject.
     */
    protected fun systemMessageRowsOf(chatId: UUID): List<Map<String, Any>> =
        jdbcTemplate.queryForList(SYSTEM_MESSAGES_OF_CHAT_SQL, chatId)

    /** The parsed invite payload of a [systemMessageRowsOf] row. */
    protected fun widgetPayloadOf(row: Map<String, Any>): JsonNode =
        objectMapper.readTree(row[PAYLOAD_TEXT_FIELD].toString())

    /** The principal rows of [conferenceId] — the SC-002 «no row before the refusal» probe. */
    protected fun participantRowsOf(conferenceId: UUID): List<Map<String, Any>> =
        jdbcTemplate.queryForList(PARTICIPANTS_OF_CONFERENCE_SQL, conferenceId)

    /**
     * Stages SOURCE moderation restrictions on a member's principal row
     * — invariant 3 (Q5=A) drives the mint from the PERSISTED state,
     * and №49 (T036) that writes it does not exist yet: the IT writes
     * the same columns the SET-семантика will own.
     */
    protected fun stageSourceRestrictions(
        conferenceId: UUID,
        userId: UUID,
        micRestricted: Boolean = false,
        cameraRestricted: Boolean = false,
        screenRestricted: Boolean = false,
    ) {
        stageRestrictionsRow(
            conferenceId,
            userId,
            ParticipantRestrictions(
                micRestricted = micRestricted,
                cameraRestricted = cameraRestricted,
                screenRestricted = screenRestricted,
            ),
        )
    }

    /** The same staging for the «право слова» (Q4=A): the speech flag alone, every source flag reset. */
    protected fun stageSpeechRestriction(
        conferenceId: UUID,
        userId: UUID,
    ) {
        stageRestrictionsRow(conferenceId, userId, SPEECH_RESTRICTIONS)
    }

    private fun stageRestrictionsRow(
        conferenceId: UUID,
        userId: UUID,
        restrictions: ParticipantRestrictions,
    ) {
        assertThat(
            jdbcTemplate.update(
                STAGE_RESTRICTIONS_SQL,
                restrictions.micRestricted,
                restrictions.cameraRestricted,
                restrictions.screenRestricted,
                restrictions.speechRestricted,
                conferenceId,
                userId,
            ),
        ).overridingErrorMessage("the restriction fixture must touch the staged principal row")
            .isEqualTo(1)
    }

    /** Marks an existing dialog chat as E2EE — the FR-001 v1 boundary fixture (`chats.e2ee`, V18). */
    protected fun stageE2eeChat(chatId: UUID) {
        assertThat(jdbcTemplate.update(STAGE_E2EE_SQL, chatId))
            .overridingErrorMessage("the e2ee fixture must flip the chats.e2ee flag of <$chatId>")
            .isEqualTo(1)
    }

    /** Awaits the media-plane roster truth of [roomName] matching [predicate] (the №45 source, research §4). */
    protected fun awaitRoomParticipants(
        roomName: String,
        predicate: (List<String>) -> Boolean,
    ) {
        await().atMost(ROOM_AWAIT_TIMEOUT).until {
            predicate(liveKitAdmin.listParticipants(roomName).map { it.identity })
        }
    }

    private fun exchangeAuthorized(
        user: MessagingUser,
        path: String,
        method: HttpMethod = HttpMethod.GET,
    ): ResponseEntity<String> =
        restTemplate.exchange(
            path,
            method,
            HttpEntity<Void>(null, HttpHeaders().apply { setBearerAuth(user.accessToken) }),
            String::class.java,
        )

    private fun postJsonAuthorized(
        user: MessagingUser,
        path: String,
        payload: Map<String, Any>,
    ): ResponseEntity<String> {
        val headers =
            HttpHeaders().apply {
                contentType = MediaType.APPLICATION_JSON
                setBearerAuth(user.accessToken)
            }
        return restTemplate.postForEntity(path, HttpEntity(payload, headers), String::class.java)
    }

    /**
     * A signal-only LiveKit room connection of a REAL №47 ticket — the
     * «two tabs» and FR-003-edge media-plane actor of the story ITs
     * (livekit-integration.md §7: the signal handshake alone registers
     * a `JOINED` participant, occupies a quota slot and honours
     * `RemoveParticipant`; media is never driven from the IT). The
     * socket is kept alive by OkHttp ping frames and must be
     * [close]d by the fixture.
     */
    protected class RoomConnection(
        wsUrl: String,
        token: String,
    ) : AutoCloseable {
        private val joined = CountDownLatch(1)

        private val ended = CountDownLatch(1)

        val webSocket: WebSocket =
            CLIENT.newWebSocket(
                Request
                    .Builder()
                    .url(rtcUrlOf(wsUrl, token))
                    .build(),
                object : WebSocketListener() {
                    override fun onMessage(
                        webSocket: WebSocket,
                        bytes: okio.ByteString,
                    ) {
                        // the first binary frame is the SignalResponse
                        // `join` — the room now counts this connection
                        joined.countDown()
                    }

                    override fun onClosing(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String,
                    ) {
                        // the SERVER-initiated graceful close — the
                        // RemoveParticipant/room-end observable (the
                        // handshake completion of onClosed may never
                        // arrive when the plane drops TCP right after
                        // its close frame)
                        ended.countDown()
                    }

                    override fun onClosed(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String,
                    ) {
                        ended.countDown()
                    }

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?,
                    ) {
                        // a server-side close surfaces here or in
                        // onClosed — both are the same «gone» for the
                        // fixture (RemoveParticipant, room end)
                        joined.countDown()
                        ended.countDown()
                    }
                },
            )

        /** Blocks until the server confirmed the join (the first signal frame) — the slot is taken then. */
        fun awaitJoined() {
            assertThat(joined.await(JOIN_AWAIT_SECONDS, TimeUnit.SECONDS))
                .overridingErrorMessage("a signal connection with a valid ticket must receive the join frame")
                .isTrue
        }

        /** Blocks until the SERVER broke the connection — the RemoveParticipant/room-end observable. */
        fun awaitClosedByServer() {
            assertThat(ended.await(JOIN_AWAIT_SECONDS, TimeUnit.SECONDS))
                .overridingErrorMessage("the media plane must break the connection of a removed participant")
                .isTrue
        }

        override fun close() {
            webSocket.close(CLOSE_CODE_NORMAL, "it-fixture-close")
        }

        private companion object {
            /** One shared WS executor for every fixture connection of the IT JVM. */
            private val CLIENT =
                OkHttpClient
                    .Builder()
                    .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
                    .readTimeout(Duration.ZERO) // a WS stream has no read deadline — pings keep it alive
                    .pingInterval(Duration.ofSeconds(PING_INTERVAL_SECONDS))
                    .build()

            /**
             * The signaling ingress of a ticket: `ws://…/rtc?access_token=…`
             * with the client params the plane expects (the probe-verified
             * minimal join of v1.13.x — no WebRTC offer needed).
             */
            fun rtcUrlOf(
                wsUrl: String,
                token: String,
            ): String =
                wsUrl
                    .replaceFirst(HTTP_SCHEME, WS_SCHEME)
                    .let { "$it/rtc?access_token=$token&auto_subscribe=0&sdk=jvm-it&version=0.0.1" }

            const val HTTP_SCHEME = "http://"

            const val WS_SCHEME = "ws://"

            const val JOIN_AWAIT_SECONDS = 10L

            const val CONNECT_TIMEOUT_SECONDS = 10L

            const val PING_INTERVAL_SECONDS = 10L

            const val CLOSE_CODE_NORMAL = 1000
        }
    }

    protected companion object {
        /**
         * The media plane of the conferences IT contexts — the
         * `deploy/local` compose shape (T003): single-node server, the
         * public placeholder pair the default
         * `conferences.livekit.api-key/secret` already mirrors, and
         * `enable_remote_unmute: true` (without it the later US3 mute
         * legs are forbidden, livekit-integration.md §1). The explicit
         * `bind_addresses: 0.0.0.0` is what makes the mapped port
         * reachable from the test JVM — the plain `--dev` flag binds
         * loopback inside the container only.
         */
        val livekit: GenericContainer<*> =
            GenericContainer(DockerImageName.parse("livekit/livekit-server:$LIVEKIT_VERSION"))
                .withEnv(LIVEKIT_CONFIG_ENV, LIVEKIT_CONFIG)
                .withExposedPorts(LIVEKIT_HTTP_PORT)
                .apply { start() }

        @DynamicPropertySource
        @JvmStatic
        fun conferenceITProperties(registry: DynamicPropertyRegistry) {
            registry.add("conferences.livekit.url") {
                "http://${livekit.host}:${livekit.getMappedPort(LIVEKIT_HTTP_PORT)}"
            }
            // research §13: the quota legs run on a lowered
            // max-participants — the 16-strong default would need 16
            // live connections to exhaust
            registry.add("conferences.max-participants") { QUOTA.toString() }
        }

        /** The FR-015 quota of every IT context: two tabs of one member + one peer fill the room. */
        const val QUOTA = 3

        const val LIVEKIT_VERSION = "v1.13.10"

        const val LIVEKIT_CONFIG_ENV = "LIVEKIT_CONFIG"

        const val LIVEKIT_HTTP_PORT = 7880

        const val LIVEKIT_CONFIG =
            """
            port: 7880
            bind_addresses:
              - 0.0.0.0
            keys:
              devkey: secret
            room:
              enable_remote_unmute: true
            """

        val ROOM_AWAIT_TIMEOUT: Duration = Duration.ofSeconds(10)

        const val PROBLEM_JSON_MEDIA_TYPE = "application/problem+json"

        const val ERRORS_FIELD = "errors"

        const val CONFERENCE_FIELD = "conference"

        const val FIRST_ELEMENT = 0

        const val MODE_FIELD = "mode"

        const val USER_ID_FIELD = "userId"

        const val TITLE_FIELD = "title"

        const val MEMBER_USER_IDS_FIELD = "memberUserIds"

        const val CHAT_ID_FIELD = "chatId"

        const val CONTACTS_PATH = "/api/v1/contacts"

        const val GROUPS_PATH = "/api/v1/groups"

        const val TITLE_SUFFIX_LENGTH = 8

        const val PAYLOAD_TEXT_FIELD = "payload_text"

        val CONFERENCES_OF_CHAT_SQL =
            """
            SELECT * FROM conferences WHERE chat_id = ?
            """.trimIndent()

        val SYSTEM_MESSAGES_OF_CHAT_SQL =
            """
            SELECT id, chat_id, sender_id, text, seq, kind, payload::text AS payload_text
            FROM messages WHERE chat_id = ? AND kind = 'system'
            """.trimIndent()

        val PARTICIPANTS_OF_CONFERENCE_SQL =
            """
            SELECT * FROM conference_participants WHERE conference_id = ?
            """.trimIndent()

        /** The «право слова» staging shape (Q4=A): speech alone, every source flag reset. */
        val SPEECH_RESTRICTIONS = ParticipantRestrictions(speechRestricted = true)

        val STAGE_RESTRICTIONS_SQL =
            """
            UPDATE conference_participants
            SET mic_restricted = ?, camera_restricted = ?, screen_restricted = ?, speech_restricted = ?
            WHERE conference_id = ? AND user_id = ?
            """.trimIndent()

        val STAGE_E2EE_SQL =
            """
            UPDATE chats SET e2ee = true WHERE id = ?
            """.trimIndent()
    }
}
