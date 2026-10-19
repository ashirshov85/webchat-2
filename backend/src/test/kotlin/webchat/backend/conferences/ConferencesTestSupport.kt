package webchat.backend.conferences

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.nimbusds.jwt.SignedJWT
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
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
import webchat.backend.conferences.livekit.LiveKitWebhookController
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

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
 *    invite widget, the principal rows);
 *  * the №48–№51 moderation HTTP legs (T040) and the №47 ticket claim
 *    probes (`videoClaimsOf`/`ticketMetadataOf`) — the US3 story IT
 *    legs of the persisted restrictions/role;
 *  * the №52–№55 guest HTTP legs (T045/T046) — the authorized
 *    moderator surface of the invite links and the PUBLIC
 *    no-Bearer surface whose per-IP buckets of T047 the [sourceIp]
 *    parameter pins (the flood legs of the US4 story IT, T048), plus
 *    the invite/guest-row JdbcTemplate probes and the «истёкшая
 *    ссылка» staging of the TTL no IT can wait out.
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

    /** The signal connections opened by the running test — closed by [closeFixtureConnections]. */
    private val openConnections = mutableListOf<RoomConnection>()

    @AfterEach
    fun closeFixtureConnections() {
        openConnections.forEach(RoomConnection::close)
        openConnections.clear()
    }

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

    /** Contract №48 `POST /api/v1/conferences/{conferenceId}/end` — raw response (T039). */
    protected fun endConference(
        user: MessagingUser,
        conferenceId: UUID,
    ): ResponseEntity<String> = sendAuthorized(HttpMethod.POST, "/api/v1/conferences/$conferenceId/end", user)

    /**
     * Contract №49 `PUT /api/v1/conferences/{conferenceId}/participants/{identity}/restrictions`
     * (T039) — raw response; the body is the FULL SET of the four
     * flags, so a `false` default IS an explicit reset
     * (api-contract.md 009 §2).
     */
    @Suppress("LongParameterList") // the four №49 flags of one fixture leg (api-contract.md 009 §2)
    protected fun setParticipantRestrictions(
        user: MessagingUser,
        conferenceId: UUID,
        identity: String,
        micRestricted: Boolean = false,
        cameraRestricted: Boolean = false,
        screenRestricted: Boolean = false,
        speechRestricted: Boolean = false,
    ): ResponseEntity<String> =
        sendAuthorized(
            HttpMethod.PUT,
            "/api/v1/conferences/$conferenceId/participants/$identity/restrictions",
            user,
            mapOf(
                MIC_RESTRICTED_FIELD to micRestricted,
                CAMERA_RESTRICTED_FIELD to cameraRestricted,
                SCREEN_RESTRICTED_FIELD to screenRestricted,
                SPEECH_RESTRICTED_FIELD to speechRestricted,
            ),
        )

    /** Contract №50 `PUT …/participants/{identity}/role` (T039) — raw response. */
    protected fun setParticipantRole(
        user: MessagingUser,
        conferenceId: UUID,
        identity: String,
        role: String,
    ): ResponseEntity<String> =
        sendAuthorized(
            HttpMethod.PUT,
            "/api/v1/conferences/$conferenceId/participants/$identity/role",
            user,
            mapOf(ROLE_FIELD to role),
        )

    /** Contract №51 `DELETE …/participants/{identity}` (T039) — raw response. */
    protected fun kickConferenceParticipant(
        user: MessagingUser,
        conferenceId: UUID,
        identity: String,
    ): ResponseEntity<String> =
        sendAuthorized(HttpMethod.DELETE, "/api/v1/conferences/$conferenceId/participants/$identity", user)

    /** Contract №52 `POST /api/v1/conferences/{conferenceId}/guest-invite` (T045) — raw response. */
    protected fun createGuestInvite(
        user: MessagingUser,
        conferenceId: UUID,
    ): ResponseEntity<String> = sendAuthorized(HttpMethod.POST, "/api/v1/conferences/$conferenceId/guest-invite", user)

    /** Contract №53 `DELETE /api/v1/conferences/{conferenceId}/guest-invite` (T045) — raw response. */
    protected fun revokeGuestInvite(
        user: MessagingUser,
        conferenceId: UUID,
    ): ResponseEntity<String> =
        sendAuthorized(
            HttpMethod.DELETE,
            "/api/v1/conferences/$conferenceId/guest-invite",
            user,
        )

    /**
     * T053/T057 (009, US5) — `POST /api/internal/conferences/livekit/webhook`,
     * the media plane's ONLY way into the backend: the RAW body bytes with
     * an optional [authHeader] (the serialized webhook JWT — NO Bearer
     * scheme, the [io.livekit.server.WebhookReceiver] shape) and the
     * [sourceIp] that pins the `rl:ip:conferences-livekit-webhook` bucket
     * of T053. The IT IS the plane: the Testcontainers media server
     * carries no `webhook.urls` config (deliberately — the deploy/local
     * compose shape only), so every lifecycle event of the US5 story ITs
     * arrives through THIS leg as a signed delivery the receiver must
     * accept, exactly the bytes the plane would send.
     */
    protected fun postWebhook(
        body: String,
        authHeader: String?,
        sourceIp: String,
    ): ResponseEntity<String> {
        val headers =
            HttpHeaders().apply {
                contentType = MediaType.APPLICATION_JSON
                authHeader?.let { set(HttpHeaders.AUTHORIZATION, it) }
                set(X_FORWARDED_FOR_HEADER, sourceIp)
            }
        // postForEntity (the Object-typed request overload): the byte[]
        // body must NOT unify the request/response type variables of the
        // exchange overloads, and ByteArrayHttpMessageConverter writes
        // the exact signature bytes under the JSON content type
        return restTemplate.postForEntity(
            LiveKitWebhookController.WEBHOOK_PATH,
            HttpEntity(body.toByteArray(StandardCharsets.UTF_8), headers),
            String::class.java,
        )
    }

    /**
     * Mints the webhook Authorization exactly like the plane does
     * (livekit-integration.md §5): a compact HS256 JWT whose `iss` is
     * the API key and whose `sha256` claim is the STANDARD-Base64 of
     * the SHA-256 over the exact body bytes — the same pair
     * [LiveKitWebhookController] verifies through the SDK receiver.
     *
     * Signed through `javax.crypto.Mac` ON PURPOSE: the dev pair
     * `devkey`/`secret` is SHORTER than the 32-byte floor of nimbus
     * `MACSigner` (a nimbus-only limitation — the plane's Go HMAC and
     * the receiver's auth0 verifier both accept any length), and the
     * SDK's own auth0 java-jwt is a runtime-only transitive of
     * livekit-server, unreferenceable from test code.
     */
    protected fun signedWebhookAuth(
        body: String,
        apiKey: String,
        apiSecret: String,
    ): String {
        val sha256 =
            Base64
                .getEncoder()
                .encodeToString(
                    MessageDigest
                        .getInstance(SHA_256_ALGORITHM)
                        .digest(body.toByteArray(StandardCharsets.UTF_8)),
                )
        val header = """{"alg":"$HS256_ALGORITHM","typ":"JWT"}"""
        val claims = """{"iss":"$apiKey","iat":${System.currentTimeMillis() / MILLIS_PER_SECOND},"sha256":"$sha256"}"""
        val signingInput = "${base64UrlOf(header)}.${base64UrlOf(claims)}"
        val mac =
            Mac.getInstance(HMAC_SHA_256).apply {
                init(SecretKeySpec(apiSecret.toByteArray(StandardCharsets.UTF_8), HMAC_SHA_256))
            }
        return "$signingInput.${base64UrlOf(mac.doFinal(signingInput.toByteArray(StandardCharsets.UTF_8)))}"
    }

    private fun base64UrlOf(value: String): String = base64UrlOf(value.toByteArray(StandardCharsets.UTF_8))

    private fun base64UrlOf(bytes: ByteArray): String =
        Base64
            .getUrlEncoder()
            .withoutPadding()
            .encodeToString(bytes)

    /**
     * Contract №54 `GET /api/v1/conferences/invite-info?inviteToken={nonce}`
     * (T046) — the PUBLIC status probe: no Bearer, only the per-IP bucket of
     * T047, so [sourceIp] pins the `rl:ip:conferences-invite-info` bucket the
     * flood legs need (a `null` [nonce] is the missing query-parameter 400
     * leg of the contract table).
     */
    protected fun inviteInfo(
        nonce: String?,
        sourceIp: String,
    ): ResponseEntity<String> {
        val path = if (nonce == null) INVITE_INFO_PATH else "$INVITE_INFO_PATH?$INVITE_TOKEN_PARAM=$nonce"
        return restTemplate.exchange(
            path,
            HttpMethod.GET,
            HttpEntity<Void>(null, HttpHeaders().apply { set(X_FORWARDED_FOR_HEADER, sourceIp) }),
            String::class.java,
        )
    }

    /**
     * Contract №55 `POST /api/v1/conferences/join-by-invite` (T046) — the
     * PUBLIC mint: no Bearer required, and an optional [asUser] Bearer rides
     * the Q14=A leg — the endpoint must treat an authenticated chat member
     * as a guest anyway. [sourceIp] pins the stricter
     * `rl:ip:conferences-join-by-invite` bucket (10/1m canon).
     */
    protected fun joinByInvite(
        nonce: String?,
        name: String?,
        sourceIp: String,
        asUser: MessagingUser? = null,
    ): ResponseEntity<String> {
        val headers =
            HttpHeaders().apply {
                contentType = MediaType.APPLICATION_JSON
                set(X_FORWARDED_FOR_HEADER, sourceIp)
                asUser?.let { setBearerAuth(it.accessToken) }
            }
        return restTemplate.postForEntity(
            JOIN_BY_INVITE_PATH,
            HttpEntity(mapOf(INVITE_TOKEN_FIELD to nonce, NAME_FIELD to name), headers),
            String::class.java,
        )
    }

    /** №52 happy-path fixture → the `GuestInviteView` body (`inviteUrl`/`expiresAt`, api-contract.md 009 §4). */
    protected fun createGuestInviteOk(
        user: MessagingUser,
        conferenceId: UUID,
    ): JsonNode {
        val response = createGuestInvite(user, conferenceId)
        assertThat(response.statusCode)
            .overridingErrorMessage("fixture №52 must answer 201, got <%s>: %s", response.statusCode, response.body)
            .isEqualTo(HttpStatus.CREATED)
        return objectMapper.readTree(response.body)
    }

    /** The nonce segment of a №52 `inviteUrl` — the `/join/{nonce}` public route payload (T050). */
    protected fun nonceOfInviteUrl(inviteUrl: String): String = inviteUrl.substringAfterLast("/")

    /** The guest principal rows of [conferenceId] — `user_id IS NULL`, one `g:{connId}` row per №55 entry (Q7=A). */
    protected fun guestRowsOf(conferenceId: UUID): List<Map<String, Any>> =
        jdbcTemplate.queryForList(GUEST_ROWS_OF_CONFERENCE_SQL, conferenceId)

    /** The invite status rows of [conferenceId] — the Q13/Q17 ladder probes of the story ITs. */
    protected fun inviteRowsOf(conferenceId: UUID): List<Map<String, Any>> =
        jdbcTemplate.queryForList(GUEST_INVITES_OF_CONFERENCE_SQL, conferenceId)

    /**
     * Stages the ACTIVE invite row of [conferenceId] past its horizon — the
     * «истёкшая ссылка» fixture: the JWT `exp` equals the row `expires_at` BY
     * CONSTRUCTION (T044 mints from the stored row), so moving both row
     * timestamps into the past (CHECKs kept: `expires_at > created_at`) is
     * exactly the passage of the 24 h TTL no IT can wait out.
     */
    protected fun stageExpiredInvite(conferenceId: UUID) {
        assertThat(jdbcTemplate.update(STAGE_EXPIRED_INVITE_SQL, conferenceId))
            .overridingErrorMessage("the expiry fixture must touch the ACTIVE invite row of <$conferenceId>")
            .isEqualTo(1)
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
     * Contract №30 `DELETE /api/v1/groups/{chatId}` — the OWNER-ONLY
     * hard delete, the T062 chat-deletion seat: the ONLY durable
     * removal of a chat (the CASCADE base the `chat_deleted`
     * conference end path rides on, FR-014).
     */
    protected fun deleteGroup(
        owner: MessagingUser,
        chatId: UUID,
    ): ResponseEntity<String> = exchangeAuthorized(owner, "$GROUPS_PATH/$chatId", HttpMethod.DELETE)

    /** The №30 happy-path fixture — asserts the durable deletion itself (the CASCADE has run). */
    protected fun deleteGroupOk(
        owner: MessagingUser,
        chatId: UUID,
    ) {
        val response = deleteGroup(owner, chatId)
        assertThat(response.statusCode)
            .overridingErrorMessage(
                "№30 delete of group <$chatId> by its owner must answer 204, got <%s>: %s",
                response.statusCode,
                response.body,
            ).isEqualTo(HttpStatus.NO_CONTENT)
    }

    /**
     * The RFC 9457 problem code of a refused №44–№55 answer — the
     * `errors: {field: [<code>]}` shape of api-contract.md 009 §1
     * (the ChatAccessIT convention); the moderation target refusals
     * ride the `participant` field, the rest — `conference`.
     */
    protected fun problemCode(
        response: ResponseEntity<String>,
        field: String = CONFERENCE_FIELD,
    ): String? {
        assertThat(response.headers.contentType?.toString())
            .overridingErrorMessage("a refused conferences answer must be RFC 9457 application/problem+json")
            .contains(PROBLEM_JSON_MEDIA_TYPE)
        return objectMapper
            .readTree(response.body)
            .get(ERRORS_FIELD)
            ?.get(field)
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

    /** №44 happy-path fixture → the conferenceId of the created row. */
    protected fun createConferenceOk(
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

    /** №47 happy-path fixture → the raw 200 response (the parsed ticket via [ticketOf]). */
    protected fun joinConferenceOk(
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
    protected fun connectMember(
        user: MessagingUser,
        conferenceId: UUID,
    ): RoomConnection = connectTicket(ticketOf(joinConferenceOk(user, conferenceId)))

    /** Opens and registers a signal connection of a minted ticket — the fixture closes it after the test. */
    protected fun connectTicket(ticket: JsonNode): RoomConnection {
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
     * The decoded `video` claim map of a minted №47 ticket — the
     * grants-table probe of the moderation legs (the ticket SIGNATURE
     * is proven by the plane accepting the connection, the T021 note).
     */
    @Suppress("UNCHECKED_CAST")
    protected fun videoClaimsOf(response: ResponseEntity<String>): Map<String, Any> =
        SignedJWT.parse(ticketOf(response)[TOKEN_FIELD].asText()).jwtClaimsSet.getClaim(VIDEO_FIELD) as Map<String, Any>

    /** The `metadata` claim of a minted №47 ticket — the persisted role surface (Q9=A). */
    protected fun ticketMetadataOf(response: ResponseEntity<String>): String? =
        SignedJWT.parse(ticketOf(response)[TOKEN_FIELD].asText()).jwtClaimsSet.getStringClaim(METADATA_FIELD)

    /** The moderation `{identity}` path segment of a member principal — `u:{userId}`. */
    protected fun memberPrincipalOf(userId: UUID): String = "u:$userId"

    /** The principal row of one member — the durable Q5/Q9/Q22 state probe, `null` before his first join. */
    protected fun memberRowOf(
        conferenceId: UUID,
        userId: UUID,
    ): Map<String, Any>? = participantRowsOf(conferenceId).singleOrNull { it[USER_ID_COLUMN] == userId }

    /** The LiveKit room name of a conference — `c:{conferenceId}` (livekit-integration.md §2). */
    protected fun roomNameOf(conferenceId: UUID): String = "$ROOM_PREFIX$conferenceId"

    /** The connection-identity prefix of one member — `u:{userId}:` (every tab matches it, edge Q10). */
    protected fun memberPrefixOf(userId: UUID): String = "u:$userId:"

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

    /** The №48–№51 authorized request leg — an optional JSON body over any method. */
    private fun sendAuthorized(
        method: HttpMethod,
        path: String,
        user: MessagingUser,
        payload: Map<String, Any>? = null,
    ): ResponseEntity<String> {
        val headers =
            HttpHeaders().apply {
                setBearerAuth(user.accessToken)
                payload?.let { contentType = MediaType.APPLICATION_JSON }
            }
        return restTemplate.exchange(path, method, HttpEntity(payload, headers), String::class.java)
    }

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
                // T021's last leg KILLS the shared plane, and every
                // distinct context configuration (the T040 recording
                // config, the later US4/US5 sets) caches its own Spring
                // context — a class scheduled AFTER the kill would bind
                // against a dead container ("Mapped port … after the
                // container is started"). The supplier self-heals:
                // Testcontainers re-creates the container (a FRESH
                // mapped port) and the new context binds the live URL.
                if (!livekit.isRunning) livekit.start()
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

        const val PARTICIPANT_FIELD = "participant"

        const val MODERATOR_REQUIRED_CODE = "moderator_required"

        const val FIRST_ELEMENT = 0

        const val MODE_FIELD = "mode"

        const val ROLE_FIELD = "role"

        const val ID_FIELD = "id"

        const val IDENTITY_FIELD = "identity"

        const val NAME_FIELD = "name"

        const val WS_URL_FIELD = "wsUrl"

        const val TOKEN_FIELD = "token"

        const val METADATA_FIELD = "metadata"

        const val VIDEO_FIELD = "video"

        const val CAN_PUBLISH_FIELD = "canPublish"

        const val CAN_PUBLISH_SOURCES_FIELD = "canPublishSources"

        const val MIC_RESTRICTED_FIELD = "micRestricted"

        const val CAMERA_RESTRICTED_FIELD = "cameraRestricted"

        const val SCREEN_RESTRICTED_FIELD = "screenRestricted"

        const val SPEECH_RESTRICTED_FIELD = "speechRestricted"

        const val ONLINE_FIELD = "online"

        const val MODERATOR_ROLE = "moderator"

        const val PARTICIPANT_ROLE = "participant"

        const val VIDEO_MODE = "video"

        const val AUDIO_MODE = "audio"

        const val CAMERA_SOURCE = "camera"

        const val ROOM_PREFIX = "c:"

        const val USER_ID_COLUMN = "user_id"

        const val ROLE_COLUMN = "role"

        const val USER_ID_FIELD = "userId"

        const val TITLE_FIELD = "title"

        const val MEMBER_USER_IDS_FIELD = "memberUserIds"

        const val CHAT_ID_FIELD = "chatId"

        const val CONTACTS_PATH = "/api/v1/contacts"

        const val GROUPS_PATH = "/api/v1/groups"

        const val TITLE_SUFFIX_LENGTH = 8

        const val PAYLOAD_TEXT_FIELD = "payload_text"

        const val X_FORWARDED_FOR_HEADER = "X-Forwarded-For"

        const val INVITE_TOKEN_FIELD = "inviteToken"

        const val INVITE_URL_FIELD = "inviteUrl"

        const val EXPIRES_AT_FIELD = "expiresAt"

        const val VALID_FIELD = "valid"

        const val REASON_FIELD = "reason"

        const val LIVE_FIELD = "live"

        const val GUEST_FIELD = "guest"

        const val INVITE_ID_FIELD = "inviteId"

        const val INVITE_FIELD = "invite"

        const val STATUS_COLUMN = "status"

        const val REVOKED_AT_COLUMN = "revoked_at"

        const val DISPLAY_NAME_COLUMN = "display_name"

        const val INVITE_INFO_PATH = "/api/v1/conferences/invite-info"

        const val JOIN_BY_INVITE_PATH = "/api/v1/conferences/join-by-invite"

        const val INVITE_TOKEN_PARAM = "inviteToken"

        /** The webhook-mint constants of [signedWebhookAuth] (§5: the compact HS256 shape the receiver verifies). */
        const val SHA_256_ALGORITHM = "SHA-256"

        const val HS256_ALGORITHM = "HS256"

        const val HMAC_SHA_256 = "HmacSHA256"

        const val MILLIS_PER_SECOND = 1000L

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

        val GUEST_ROWS_OF_CONFERENCE_SQL =
            """
            SELECT * FROM conference_participants WHERE conference_id = ? AND user_id IS NULL
            """.trimIndent()

        val GUEST_INVITES_OF_CONFERENCE_SQL =
            """
            SELECT * FROM conference_guest_invites WHERE conference_id = ?
            """.trimIndent()

        val STAGE_EXPIRED_INVITE_SQL =
            """
            UPDATE conference_guest_invites
            SET created_at = now() - interval '2 days', expires_at = now() - interval '1 day'
            WHERE conference_id = ? AND status = 'active'
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
