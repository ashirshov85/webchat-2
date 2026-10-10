package webchat.backend.chats

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.yaml.snakeyaml.Yaml
import webchat.backend.config.ChatsProperties
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID

/**
 * T051 (tasks.md Phase 6, US4): the per-chat sound toggle of 008a —
 * contract №42 `PUT /chats/{chatId}/sound`, the own-channel-only
 * `chat.sound.updated` frame of №18 and the `soundEnabled` projections
 * of №11/№12/№13 (api-contract.md §1 №42 + §2,
 * realtime-events.md §1.3, FR-012–FR-015, SC-006) — over the real
 * stack (Testcontainers PG+Redis through [MessagingTestSupport]):
 *
 *  * SC-006 (server proxy leg, the TypingIT start→typing.started
 *    precedent): a member's №42 CHANGE answers `200
 *    ChatSoundResponse {soundEnabled}` and his OWN stream receives
 *    `chat.sound.updated {chatId, soundEnabled}` — payload conforming
 *    to the LIVE `components.schemas.ChatSoundUpdatedEvent` of
 *    contracts/openapi.yaml — within the 2 s budget measured from the
 *    №42 PUT (the p95 percentile and the client render are the manual
 *    T062 check); toggling back publishes the second change frame the
 *    same way;
 *  * idempotency: a repeat of the ALREADY STORED value (the fresh
 *    default `true` included) still answers `200` with the stored
 *    `ChatSoundResponse` but publishes NOTHING — only a genuine change
 *    is an event;
 *  * privacy (FR-012): the PEER's stream never carries
 *    `chat.sound.updated` — the value and the very fact of the toggle
 *    are the caller's own business, never the other participants';
 *  * projections (api-contract.md §2): the caller's `soundEnabled`
 *    rides №11 (ChatView), №12 (ChatListItem) and №13 (ChatView) —
 *    the server sets it ALWAYS, `true` for a fresh participation
 *    (data-model.md §1.3 default), flipped by №42 — while the PEER's
 *    own views of the very same dialog keep HIS untouched `true`
 *    (per-user row, zero leakage);
 *  * the №13/№16 access matrix holds on №42: a registered stranger
 *    gets `403 not_participant`, an unknown chat id `404
 *    chat_not_found`, a non-UUID path `400 invalid_uuid`, and a
 *    missing/non-boolean `enabled` answers `400
 *    errors.enabled=[malformed_request]` — every refusal an RFC 9457
 *    problem carrying the contract code;
 *  * the №42 flood bucket `rl:user:chat-sound:{userId}` (30/min, the
 *    №38 parity, research.md D2): the burst past the allowance is
 *    refused `429 flood_limit` + integral `Retry-After ≥ 1` with
 *    `errors.enabled=[flood_limit]`, and the bucket is strictly
 *    per-user — a different user toggles right after undisturbed.
 *
 * NOTE (TDD, constitution VI): written BEFORE T052/T053 — until the
 * `chat_participants.sound_enabled` read/write legs and the №42
 * controller land, every scenario here fails (RED) by design at the
 * awaited `200`/frame/`soundEnabled` projection (the route itself
 * answers 404 today); only the flood-capacity guard passes (the
 * `chats.rate-limit.sound-writes-per-minute` config landed with T008).
 */
@Suppress("LargeClass", "TooManyFunctions") // T051: one scenario per US4 acceptance + the №42/№18/№12/№13 fixtures
class ChatSoundIT(
    @Autowired private val restTemplate: TestRestTemplate,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val chatsProperties: ChatsProperties,
) : MessagingTestSupport() {
    /**
     * T008 guard: the flood leg drains the REAL №42 bucket — the IT
     * profile must keep the production 30/min capacity (a profile drift
     * must fail loudly, the TypingIT window-guard precedent).
     */
    @Test
    fun `it profile keeps the production sound-toggle flood capacity the burst drains`() {
        assertThat(chatsProperties.rateLimit.soundWritesPerMinute)
            .overridingErrorMessage(
                "the №42 flood capacity must keep the production 30/min (research.md D2) " +
                    "— the flood leg drains the real bucket (T008), got <%s>",
                chatsProperties.rateLimit.soundWritesPerMinute,
            ).isEqualTo(PROFILE_SOUND_WRITES_PER_MINUTE)
    }

    /**
     * SC-006 (server proxy leg) + privacy (FR-012): the member's №42
     * change answers `200 ChatSoundResponse`, his OWN stream carries
     * `chat.sound.updated {chatId, soundEnabled}` (payload validated
     * against the live `ChatSoundUpdatedEvent` schema, EXACTLY the two
     * contract fields, naming the toggled dialog and the NEW value)
     * within the 2 s budget measured from the very PUT — while the
     * PEER's stream stays silent: the other participant never learns
     * of the toggle. Toggling BACK publishes the second change frame
     * through the same own-channel leg (every genuine change is an
     * event — the multi-device sync of FR-015).
     */
    @Test
    fun `a change reaches the own channel within two seconds and never the peer`() {
        val (alice, bob) = messagingPair()
        val chatId = ensureChatOk(alice, bob.id)

        openUserEvents(bob).use { bobStream ->
            openUserEvents(alice).use { aliceStream ->
                val sentAt = System.nanoTime()
                val muted = setChatSound(alice, chatId, enabled = false)
                assertThat(muted.statusCode)
                    .overridingErrorMessage(
                        "№42 of a chat member must answer 200, got <%s>: %s",
                        muted.statusCode,
                        muted.body,
                    ).isEqualTo(HttpStatus.OK)
                assertSoundResponseBody(muted, expectedSoundEnabled = false)

                val mutedFrame = aliceStream.awaitEvent(CHAT_SOUND_EVENT, SC006_BUDGET)
                val deliveryTook = Duration.ofNanos(System.nanoTime() - sentAt)
                assertThat(deliveryTook)
                    .overridingErrorMessage(
                        "SC-006: chat.sound.updated must reach the toggler's own channel " +
                            "within 2 s of the №42 PUT, took <%s>",
                        deliveryTook,
                    ).isLessThanOrEqualTo(SC006_BUDGET)
                assertSoundUpdatedPayload(mutedFrame, chatId, expectedSoundEnabled = false)

                // Toggling back is a genuine change too — the second own-channel frame.
                assertThat(setChatSound(alice, chatId, enabled = true).statusCode)
                    .isEqualTo(HttpStatus.OK)
                val unmutedFrame = aliceStream.awaitEvent(CHAT_SOUND_EVENT, SC006_BUDGET)
                assertSoundUpdatedPayload(unmutedFrame, chatId, expectedSoundEnabled = true)

                // FR-012 privacy: the peer's stream never carries the toggle frames.
                assertNoFrameOfFollows(bobStream, NO_FRAME_WINDOW, CHAT_SOUND_EVENT)
            }
        }
    }

    /**
     * Idempotency (api-contract.md №42): a repeat of the ALREADY STORED
     * value — the fresh default `true` BEFORE any change, and the just
     * stored `false` AFTER one — still answers `200` with the stored
     * `ChatSoundResponse` but publishes NOTHING: only a genuine change
     * is an event.
     */
    @Test
    fun `a repeated value answers 200 with the stored body but no new event`() {
        val (alice, bob) = messagingPair()
        val chatId = ensureChatOk(alice, bob.id)

        openUserEvents(alice).use { stream ->
            // The fresh participation defaults to true — repeating it is a no-op.
            val repeatDefault = setChatSound(alice, chatId, enabled = true)
            assertThat(repeatDefault.statusCode)
                .overridingErrorMessage(
                    "a repeated №42 of the stored default must stay ACCEPTED (200), got <%s>: %s",
                    repeatDefault.statusCode,
                    repeatDefault.body,
                ).isEqualTo(HttpStatus.OK)
            assertSoundResponseBody(repeatDefault, expectedSoundEnabled = true)
            assertNoFrameOfFollows(stream, NO_FRAME_WINDOW, CHAT_SOUND_EVENT)

            // A genuine change lands exactly one frame…
            assertThat(setChatSound(alice, chatId, enabled = false).statusCode).isEqualTo(HttpStatus.OK)
            assertSoundUpdatedPayload(stream.awaitEvent(CHAT_SOUND_EVENT, FRAME_BUDGET), chatId, false)

            // …and repeating the new value publishes nothing more.
            val repeatNew = setChatSound(alice, chatId, enabled = false)
            assertThat(repeatNew.statusCode)
                .overridingErrorMessage(
                    "a repeated №42 of the just stored value must stay ACCEPTED (200), got <%s>: %s",
                    repeatNew.statusCode,
                    repeatNew.body,
                ).isEqualTo(HttpStatus.OK)
            assertSoundResponseBody(repeatNew, expectedSoundEnabled = false)
            assertNoFrameOfFollows(stream, NO_FRAME_WINDOW, CHAT_SOUND_EVENT)
        }
    }

    /**
     * api-contract.md §2 (FR-012, data-model.md §1.3): the caller's
     * PERSONAL `soundEnabled` rides №11 (ensure → ChatView), №12
     * (ChatListItem) and №13 (get → ChatView) — the server sets it
     * ALWAYS, `true` for a fresh participation, flipped by his own №42
     * — while the PEER's views of the very same dialog keep HIS
     * untouched `true`: the toggle never leaks into the other side's
     * projections.
     */
    @Test
    fun `soundEnabled rides the №11 №12 №13 projections of the caller only`() {
        val (alice, bob) = messagingPair()
        val chatId = ensureChatOk(alice, bob.id)

        // Fresh participation: the server sets soundEnabled = true on every view.
        val ensured = soundEnabledOf(chatViewOf(ensureChat(alice, bob.id), chatId), surface = "№11")
        assertThat(ensured)
            .overridingErrorMessage("№11 must carry the fresh-participation default soundEnabled = true")
            .isTrue
        assertThat(soundEnabledOf(chatListItemOf(alice, chatId), surface = "№12"))
            .overridingErrorMessage("№12 must carry the fresh-participation default soundEnabled = true")
            .isTrue
        assertThat(soundEnabledOf(chatViewOf(getChat(alice, chatId), chatId), surface = "№13"))
            .overridingErrorMessage("№13 must carry the fresh-participation default soundEnabled = true")
            .isTrue

        // The toggle flips the caller's own projections everywhere…
        assertThat(setChatSound(alice, chatId, enabled = false).statusCode).isEqualTo(HttpStatus.OK)
        assertThat(soundEnabledOf(chatViewOf(ensureChat(alice, bob.id), chatId), surface = "№11"))
            .overridingErrorMessage("№11 must serve the toggled soundEnabled = false of the caller")
            .isFalse
        assertThat(soundEnabledOf(chatListItemOf(alice, chatId), surface = "№12"))
            .overridingErrorMessage("№12 must serve the toggled soundEnabled = false of the caller")
            .isFalse
        assertThat(soundEnabledOf(chatViewOf(getChat(alice, chatId), chatId), surface = "№13"))
            .overridingErrorMessage("№13 must serve the toggled soundEnabled = false of the caller")
            .isFalse

        // …and NEVER the peer's own views of the same dialog (per-user row, FR-012).
        assertThat(soundEnabledOf(chatViewOf(getChat(bob, chatId), chatId), surface = "№13 of the peer"))
            .overridingErrorMessage("the peer's №13 must keep HIS OWN default soundEnabled = true — no leakage")
            .isTrue
        assertThat(soundEnabledOf(chatListItemOf(bob, chatId), surface = "№12 of the peer"))
            .overridingErrorMessage("the peer's №12 must keep HIS OWN default soundEnabled = true — no leakage")
            .isTrue
    }

    /**
     * The №13/№16 access matrix holds on №42 plus the body guard: a
     * registered stranger gets `403 not_participant`, an unknown chat
     * id `404 chat_not_found`, a non-UUID chatId in the path `400
     * invalid_uuid`, and a missing/non-boolean `enabled` answers `400
     * errors.enabled=[malformed_request]` — every refusal an RFC 9457
     * problem carrying the contract code.
     */
    @Test
    fun `strangers unknown chats and invalid bodies get the contract refusals`() {
        val (alice, bob) = messagingPair()
        val carol = strangerUser()
        val chatId = ensureChatOk(alice, bob.id)

        assertProblemCode(
            setChatSound(carol, chatId, enabled = false),
            HttpStatus.FORBIDDEN,
            CHAT_FIELD,
            NOT_PARTICIPANT,
        )
        assertProblemCode(
            setChatSound(alice, UUID.randomUUID(), enabled = false),
            HttpStatus.NOT_FOUND,
            CHAT_FIELD,
            CHAT_NOT_FOUND,
        )
        assertProblemCode(
            setChatSoundRawChatId(alice, NOT_A_UUID, enabled = false),
            HttpStatus.BAD_REQUEST,
            CHAT_ID_FIELD,
            INVALID_UUID,
        )
        assertProblemCode(
            setChatSoundOmittingField(alice, chatId),
            HttpStatus.BAD_REQUEST,
            ENABLED_FIELD,
            MALFORMED_REQUEST,
        )
        assertProblemCode(
            setChatSoundNonBoolean(alice, chatId),
            HttpStatus.BAD_REQUEST,
            ENABLED_FIELD,
            MALFORMED_REQUEST,
        )
    }

    /**
     * The №42 flood bucket `rl:user:chat-sound:{userId}` (30/min, the
     * №38 parity, research.md D2): the drain burst must spend the FULL
     * allowance before any 429; the greedy 30/60s drip re-grants tokens
     * mid-burst, so the first refusal lands at write 31+ within
     * `30 + elapsed/2 + 1` — never at a fixed index (the
     * ProfileDisplayNameIT idiom). The refusal is the contract 429:
     * problem+json with `errors.enabled=[flood_limit]` and an integral
     * `Retry-After` of at least 1 second; the bucket is strictly
     * per-user, so a DIFFERENT user toggles right after undisturbed.
     */
    @Test
    fun `toggles past the thirty-per-minute bucket are refused 429 flood_limit with Retry-After`() {
        val (flooder, flooderPeer) = messagingPair()
        val flooderChat = ensureChatOk(flooder, flooderPeer.id)
        val (bystander, bystanderPeer) = messagingPair()
        val bystanderChat = ensureChatOk(bystander, bystanderPeer.id)

        var accepted = 0
        var rejection: ResponseEntity<String>? = null
        val startedAt = System.nanoTime()
        var attempt = 0
        while (rejection == null && attempt < FLOOD_BURST_ATTEMPT_CAP) {
            attempt += 1
            // Alternate the value: every accepted write is a genuine change.
            val response = setChatSound(flooder, flooderChat, enabled = attempt % FLOOD_PARITY == 0)
            if (response.statusCode == HttpStatus.OK) {
                accepted += 1
            } else {
                rejection = response
            }
        }
        val elapsedSeconds = (System.nanoTime() - startedAt) / NANOS_PER_SECOND
        assertThat(rejection)
            .overridingErrorMessage(
                "the 30-writes-per-minute №42 bucket must refuse the burst of %d toggles, " +
                    "but every attempt answered 200 — the flood limit is not enforced on №42",
                FLOOD_BURST_ATTEMPT_CAP,
            ).isNotNull
        val dripBound = FLOOD_WINDOW.toLong() + elapsedSeconds / 2 + FLOOD_DRIP_SLACK
        assertThat(accepted.toLong())
            .overridingErrorMessage(
                "the first refusal must land at write 31+ of the window and within the greedy-drip bound " +
                    "30+elapsed/2+1 (accepted=<%d>, elapsed=<%ds>, bound=<%d>)",
                accepted,
                elapsedSeconds,
                dripBound,
            ).isBetween(FLOOD_WINDOW.toLong(), dripBound)

        assertFloodRejection(rejection!!)
        assertThat(setChatSound(bystander, bystanderChat, enabled = false).statusCode)
            .overridingErrorMessage("the №42 flood bucket is per-user — a different user must toggle undisturbed")
            .isEqualTo(HttpStatus.OK)
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    /** Contract №42 `PUT /api/v1/chats/{chatId}/sound` — raw response. */
    private fun setChatSound(
        user: MessagingUser,
        chatId: UUID,
        enabled: Boolean,
    ): ResponseEntity<String> = setChatSoundRawChatId(user, chatId.toString(), enabled)

    /** №42 with an unvalidated chat id string — the `invalid_uuid` path leg. */
    private fun setChatSoundRawChatId(
        user: MessagingUser,
        chatId: String,
        enabled: Boolean,
    ): ResponseEntity<String> = putJson(SOUND_PATH_FORMAT.format(chatId), mapOf(ENABLED_FIELD to enabled), user)

    /** №42 with the `enabled` field OMITTED entirely — the `malformed_request` body leg. */
    private fun setChatSoundOmittingField(
        user: MessagingUser,
        chatId: UUID,
    ): ResponseEntity<String> = putJson(SOUND_PATH_FORMAT.format(chatId.toString()), emptyMap(), user)

    /** №42 with a NON-BOOLEAN `enabled` — the second `malformed_request` body leg. */
    private fun setChatSoundNonBoolean(
        user: MessagingUser,
        chatId: UUID,
    ): ResponseEntity<String> =
        putJson(SOUND_PATH_FORMAT.format(chatId.toString()), mapOf(ENABLED_FIELD to NOT_A_BOOLEAN), user)

    /**
     * The №42 200 body of api-contract.md: conforms to the LIVE
     * `ChatSoundResponse` schema and carries EXACTLY the stored
     * `soundEnabled` value.
     */
    private fun assertSoundResponseBody(
        response: ResponseEntity<String>,
        expectedSoundEnabled: Boolean,
    ) {
        val body = objectMapper.readTree(response.body!!)
        assertConformsToSchema(body, CHAT_SOUND_RESPONSE_SCHEMA)
        assertThat(fieldNames(body))
            .overridingErrorMessage("the №42 body must have exactly the contract fields")
            .containsExactly(SOUND_ENABLED_FIELD)
        assertThat(body[SOUND_ENABLED_FIELD].asBoolean())
            .overridingErrorMessage("the №42 body must echo the stored soundEnabled")
            .isEqualTo(expectedSoundEnabled)
    }

    /**
     * The `chat.sound.updated` payload contract: validates against the
     * LIVE schema, carries EXACTLY `chatId`+`soundEnabled`, names the
     * toggled dialog and the NEW value.
     */
    private fun assertSoundUpdatedPayload(
        payload: JsonNode,
        expectedChatId: UUID,
        expectedSoundEnabled: Boolean,
    ) {
        assertConformsToSchema(payload, CHAT_SOUND_UPDATED_EVENT_SCHEMA)
        assertThat(fieldNames(payload))
            .overridingErrorMessage(
                "the <%s> payload must have exactly the contract fields",
                CHAT_SOUND_UPDATED_EVENT_SCHEMA,
            ).containsExactlyInAnyOrder(CHAT_ID_FIELD, SOUND_ENABLED_FIELD)
        assertThat(payload[CHAT_ID_FIELD].asText())
            .overridingErrorMessage("the sound payload must name the dialog the toggle belongs to")
            .isEqualTo(expectedChatId.toString())
        assertThat(payload[SOUND_ENABLED_FIELD].asBoolean())
            .overridingErrorMessage("the sound payload must carry the NEW soundEnabled value")
            .isEqualTo(expectedSoundEnabled)
    }

    /** The `soundEnabled` projection leg: the field must be PRESENT and BOOLEAN (the server sets it always). */
    private fun soundEnabledOf(
        view: JsonNode,
        surface: String,
    ): Boolean {
        val field = view.path(SOUND_ENABLED_FIELD)
        assertThat(field.isBoolean)
            .overridingErrorMessage(
                "<%s> must carry the OPTIONAL-but-always-set boolean soundEnabled (api-contract.md §2), got <%s>",
                surface,
                field,
            ).isTrue
        return field.asBoolean()
    }

    /** №11/№13 happy-path body → the direct ChatView, validated against the live contract schema. */
    private fun chatViewOf(
        response: ResponseEntity<String>,
        chatId: UUID,
    ): JsonNode {
        assertThat(response.statusCode)
            .overridingErrorMessage("№11/№13 must answer 200, got <%s>: %s", response.statusCode, response.body)
            .isEqualTo(HttpStatus.OK)
        val view = objectMapper.readTree(response.body!!)
        assertThat(view[CHAT_ID_FIELD].asText())
            .overridingErrorMessage("the view must name the fixture dialog")
            .isEqualTo(chatId.toString())
        assertConformsToSchema(view, CHAT_VIEW_SCHEMA)
        return view
    }

    /** The caller's №12 item of [chatId], validated against the live ChatListItem schema. */
    private fun chatListItemOf(
        user: MessagingUser,
        chatId: UUID,
    ): JsonNode {
        val response = listChats(user)
        assertThat(response.statusCode)
            .overridingErrorMessage("№12 GET /chats must answer 200, got <%s>: %s", response.statusCode, response.body)
            .isEqualTo(HttpStatus.OK)
        val item =
            objectMapper.readTree(response.body!!)[CHATS_FIELD].firstOrNull {
                it[CHAT_ID_FIELD].asText() == chatId.toString()
            }
        assertThat(item)
            .overridingErrorMessage("the №12 list must contain chat <%s>", chatId)
            .isNotNull
        assertConformsToSchema(item!!, CHAT_LIST_ITEM_SCHEMA)
        return item
    }

    /**
     * Consumes frames for [window] and fails the moment an `event:`
     * frame of any [eventTypes] shows up — every lawful push lands well
     * inside the ≤2 s SC-006 budget, so nothing may follow after it. A
     * per-frame timeout (no COMPLETE frame within the window) is the
     * expected clean exit (the RealtimeSseIT idiom).
     */
    private fun assertNoFrameOfFollows(
        stream: UserEventsStream,
        window: Duration,
        vararg eventTypes: String,
    ) {
        val deadline = System.nanoTime() + window.toNanos()
        while (true) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return
            val frame =
                try {
                    stream.nextFrame(Duration.ofNanos(remaining))
                        ?: throw AssertionError(
                            "stream ended while asserting that no ${eventTypes.toList()} frame follows",
                        )
                } catch (failure: AssertionError) {
                    if (TIMEOUT_MARKER !in failure.message.orEmpty()) throw failure
                    return
                }
            assertThat(frame.event)
                .overridingErrorMessage(
                    "no further <%s> frame may follow within %s",
                    eventTypes.toList(),
                    window,
                ).isNotIn(eventTypes.toList())
        }
    }

    /** The exact №42 429 of api-contract.md: problem+json with
     *  `errors.enabled=[flood_limit]` + integral `Retry-After` ≥ 1s. */
    private fun assertFloodRejection(rejection: ResponseEntity<String>) {
        assertThat(rejection.statusCode)
            .overridingErrorMessage(
                "the №42 flood refusal must be 429, got <%s>: %s",
                rejection.statusCode,
                rejection.body,
            ).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
        assertThat(rejection.headers.contentType?.toString())
            .overridingErrorMessage("the №42 flood refusal must be RFC 9457 application/problem+json")
            .contains(PROBLEM_JSON_MEDIA_TYPE)
        val retryAfterHeader = rejection.headers.getFirst(HttpHeaders.RETRY_AFTER)
        val retryAfterSeconds = retryAfterHeader?.trim()?.toLongOrNull()
        assertThat(retryAfterSeconds)
            .overridingErrorMessage(
                "Retry-After must be integral seconds ≥ 1 (openapi №42), got <%s>",
                retryAfterHeader,
            ).isNotNull
        assertThat(retryAfterSeconds!!)
            .overridingErrorMessage(
                "Retry-After must be within one refill window (≥1s, ≤60s), got <%s>",
                retryAfterHeader,
            ).isBetween(RETRY_AFTER_FLOOR_SECONDS, RETRY_AFTER_CEILING_SECONDS)
        val problem = objectMapper.readTree(rejection.body)
        val codes = problem["errors"]?.get(ENABLED_FIELD)?.map { it.asText() }.orEmpty()
        assertThat(codes)
            .overridingErrorMessage(
                "the problem must carry the contract code errors.%s=[%s], got <%s> in %s",
                ENABLED_FIELD,
                FLOOD_LIMIT,
                codes,
                rejection.body,
            ).containsExactly(FLOOD_LIMIT)
    }

    /** The RFC 9457 refusal shape shared by every №42 error leg: status + `errors.{field}=[code]`. */
    private fun assertProblemCode(
        response: ResponseEntity<String>,
        expectedStatus: HttpStatus,
        field: String,
        code: String,
    ) {
        assertThat(response.statusCode)
            .overridingErrorMessage(
                "№42 must answer <%s>, got <%s>: %s",
                expectedStatus,
                response.statusCode,
                response.body,
            ).isEqualTo(expectedStatus)
        assertThat(response.headers.contentType?.toString())
            .overridingErrorMessage("№42 errors must be RFC 9457 application/problem+json")
            .contains(PROBLEM_JSON_MEDIA_TYPE)
        val problem = objectMapper.readTree(response.body)
        val codes = problem["errors"]?.get(field)?.map { it.asText() }.orEmpty()
        assertThat(codes)
            .overridingErrorMessage(
                "the problem must carry the contract code errors.%s=[%s], got <%s> in %s",
                field,
                code,
                codes,
                response.body,
            ).containsExactly(code)
    }

    private fun putJson(
        path: String,
        payload: Map<String, Any>,
        user: MessagingUser,
    ): ResponseEntity<String> =
        restTemplate.exchange(
            path,
            HttpMethod.PUT,
            HttpEntity(payload, jsonHeaders(user)),
            String::class.java,
        )

    private fun jsonHeaders(user: MessagingUser): HttpHeaders =
        HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            setBearerAuth(user.accessToken)
        }

    private fun fieldNames(node: JsonNode): List<String> = node.fieldNames().asSequence().toList()

    /**
     * T051 (constitution IV): asserts a live №42 body / №18 payload /
     * №11–№13 projection against the very schema of the public
     * contract — `contracts/openapi.yaml`
     * `components.schemas.<schemaName>` — instead of a hand-copied
     * field list, so a schema change that breaks the wire payload fails
     * here first. The validator covers exactly the JSON Schema subset
     * the 008a schemas use (the RealtimeSseIT T064 subset): `type`,
     * `required`, `properties`, `additionalProperties: false`, `$ref`,
     * `format` (`uuid`/`date-time`), `maxLength`, `minimum`.
     */
    private fun assertConformsToSchema(
        payload: JsonNode,
        schemaName: String,
    ) {
        val schema = openApiContract["components"]["schemas"][schemaName]
        if (schema !is ObjectNode) {
            throw AssertionError(
                "contracts/openapi.yaml must keep components.schemas.$schemaName — " +
                    "the №42/№18 sound payloads are declared there (api-contract.md §1/§2), got: ${schema.nodeType}",
            )
        }
        val violations = mutableListOf<String>()
        validateAgainst(payload, schema, "#", violations)
        assertThat(violations)
            .overridingErrorMessage(
                "payload violates components.schemas.%s of contracts/openapi.yaml: %s",
                schemaName,
                violations,
            ).isEmpty()
    }

    private fun validateAgainst(
        node: JsonNode,
        schema: JsonNode,
        path: String,
        violations: MutableList<String>,
    ) {
        val ref = schema["\$ref"]?.takeIf { it.isTextual }
        if (ref != null) {
            val target = resolveLocalRef(ref.asText(), path, violations)
            if (target != null) validateAgainst(node, target, path, violations)
            return
        }
        when (schema["type"]?.takeIf { it.isTextual }?.asText()) {
            "object" -> validateObject(node, schema, path, violations)
            "string" -> validateString(node, schema, path, violations)
            "integer" -> validateInteger(node, schema, path, violations)
            // a schema without a type imposes no structural constraint here
            else -> Unit
        }
    }

    private fun resolveLocalRef(
        ref: String,
        path: String,
        violations: MutableList<String>,
    ): JsonNode? {
        val target = openApiContract.at(ref.removePrefix(LOCAL_REF_PREFIX)).takeUnless { it.isMissingNode }
        return when {
            !ref.startsWith(LOCAL_REF_PREFIX) -> {
                violations.add("$path: only local schema refs are supported, got <$ref>")
                null
            }
            target == null -> {
                violations.add("$path: unresolvable schema ref <$ref>")
                null
            }
            else -> target
        }
    }

    private fun validateObject(
        node: JsonNode,
        schema: JsonNode,
        path: String,
        violations: MutableList<String>,
    ) {
        if (!node.isObject) {
            violations.add("$path: expected an object, got <${node.nodeType}>")
            return
        }
        schema["required"]?.takeIf { it.isArray }?.forEach { requiredField ->
            if (!node.has(requiredField.asText())) {
                violations.add("$path: required field <${requiredField.asText()}> is missing")
            }
        }
        val additionalForbidden =
            schema["additionalProperties"]?.takeIf { it.isBoolean }?.asBoolean() == false
        val properties = schema["properties"]?.takeIf { it.isObject }
        node.fieldNames().asSequence().forEach { field ->
            val propertySchema = properties?.get(field)
            when {
                propertySchema != null && !propertySchema.isMissingNode ->
                    validateAgainst(node[field], propertySchema, "$path/$field", violations)
                additionalForbidden ->
                    violations.add("$path/$field: not declared by the schema and additionalProperties is false")
                else -> Unit
            }
        }
    }

    private fun validateString(
        node: JsonNode,
        schema: JsonNode,
        path: String,
        violations: MutableList<String>,
    ) {
        if (!node.isTextual) {
            violations.add("$path: expected a string, got <${node.nodeType}>")
            return
        }
        val text = node.asText()
        val maxLength = schema["maxLength"]?.takeIf { it.isInt }?.asInt()
        if (maxLength != null && text.length > maxLength) {
            violations.add("$path: <${text.length}> chars exceeds maxLength <$maxLength>")
        }
        when (schema["format"]?.takeIf { it.isTextual }?.asText()) {
            "uuid" ->
                if (runCatching { UUID.fromString(text) }.isFailure) {
                    violations.add("$path: <$text> is not a uuid")
                }
            "date-time" ->
                if (runCatching { java.time.OffsetDateTime.parse(text) }.isFailure) {
                    violations.add("$path: <$text> is not an RFC 3339 date-time")
                }
        }
    }

    private fun validateInteger(
        node: JsonNode,
        schema: JsonNode,
        path: String,
        violations: MutableList<String>,
    ) {
        if (!node.isIntegralNumber || !node.canConvertToLong()) {
            violations.add("$path: expected an int64 integer, got <${node.asText()}>")
            return
        }
        val minimum = schema["minimum"]?.takeIf { it.isIntegralNumber }?.asLong()
        if (minimum != null && node.asLong() < minimum) {
            violations.add("$path: <${node.asLong()}> is below the schema minimum <$minimum>")
        }
    }

    private companion object {
        const val PROBLEM_JSON_MEDIA_TYPE = "application/problem+json"
        const val TIMEOUT_MARKER = "timed out"

        /** realtime-events.md §1.3: the own-channel-only №18 frame of 008a US4. */
        const val CHAT_SOUND_EVENT = "chat.sound.updated"
        const val CHAT_SOUND_UPDATED_EVENT_SCHEMA = "ChatSoundUpdatedEvent"
        const val CHAT_SOUND_RESPONSE_SCHEMA = "ChatSoundResponse"
        const val CHAT_VIEW_SCHEMA = "ChatView"
        const val CHAT_LIST_ITEM_SCHEMA = "ChatListItem"

        const val CHAT_ID_FIELD = "chatId"
        const val SOUND_ENABLED_FIELD = "soundEnabled"
        const val ENABLED_FIELD = "enabled"
        const val CHATS_FIELD = "chats"
        const val CHAT_FIELD = "chat"

        /** The local `$ref` prefix of the contract validator (the T064 subset). */
        const val LOCAL_REF_PREFIX = "#/"

        const val NOT_PARTICIPANT = "not_participant"
        const val CHAT_NOT_FOUND = "chat_not_found"
        const val INVALID_UUID = "invalid_uuid"
        const val MALFORMED_REQUEST = "malformed_request"
        const val FLOOD_LIMIT = "flood_limit"

        const val SOUND_PATH_FORMAT = "/api/v1/chats/%s/sound"

        const val NOT_A_UUID = "definitely-not-a-uuid"
        const val NOT_A_BOOLEAN = "loud"

        /** T008: the flood capacity keeps the production value — the flood leg drains the real bucket. */
        const val PROFILE_SOUND_WRITES_PER_MINUTE = 30

        /** api-contract.md §1 №42: 30 toggles per minute per user (research.md D2). */
        const val FLOOD_WINDOW = 30
        const val FLOOD_BURST_ATTEMPT_CAP = 40
        const val FLOOD_DRIP_SLACK = 1L
        const val FLOOD_PARITY = 2

        const val RETRY_AFTER_FLOOR_SECONDS = 1L
        const val RETRY_AFTER_CEILING_SECONDS = 60L
        const val NANOS_PER_SECOND = 1_000_000_000L

        /** SC-006: from the №42 PUT to the toggler's own frame (the TypingIT SC001 precedent). */
        val SC006_BUDGET: Duration = Duration.ofSeconds(2)

        /**
         * Any lawful push lands well inside the ≤2 s SC-006 budget — the
         * «nothing may follow» window (the 004/006 NO_FRAME_WINDOW
         * convention).
         */
        val NO_FRAME_WINDOW: Duration = Duration.ofSeconds(3)

        val FRAME_BUDGET: Duration = Duration.ofSeconds(5)

        /** `backend/` (Gradle) and the repo root (IDE runs) in order. */
        val CONTRACT_LOCATIONS: List<Path> =
            listOf(Path.of("../contracts/openapi.yaml"), Path.of("contracts/openapi.yaml"))

        val contractJson: ObjectMapper = ObjectMapper()

        /** contracts/openapi.yaml parsed once per JVM (the T064 loader). */
        val openApiContract: JsonNode by lazy { loadOpenApiContract() }

        private fun loadOpenApiContract(): JsonNode {
            val contract =
                CONTRACT_LOCATIONS.firstOrNull { Files.isRegularFile(it) }
                    ?: throw IllegalStateException(
                        "contracts/openapi.yaml not found at ${CONTRACT_LOCATIONS.joinToString()} — " +
                            "the T051 conformance check must read the live public contract",
                    )
            return contract.toFile().inputStream().use { input ->
                val yamlRoot: Any? = Yaml().load(input)
                contractJson.valueToTree<JsonNode>(yamlRoot)
            }
        }
    }
}
