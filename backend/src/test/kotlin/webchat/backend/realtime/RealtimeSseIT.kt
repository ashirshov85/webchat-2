package webchat.backend.realtime

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.yaml.snakeyaml.Yaml
import webchat.backend.chats.MessagingTestSupport
import webchat.backend.config.ChatsProperties
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/**
 * T007 (tasks.md Phase 3, US1-3): the SSE user event stream of contract №18
 * `GET /api/v1/users/me/events` answers with the exact WHATWG framing of
 * realtime-channel.md §1–§3 — the opening `retry: 3000` frame, the `:ka`
 * heartbeat comment at the `chats.realtime.heartbeat` cadence, and the
 * `message.created` event delivered to BOTH participants after a №16 POST,
 * with a payload that conforms to `components.schemas.MessageCreatedEvent`
 * (openapi.yaml: exactly `chatId` + `message`, `additionalProperties: false`)
 * and equals the stored `Message` returned by №16.
 *
 * T064 (tasks.md Phase 8, US6) extends the same IT with the contract
 * conformance gate of quickstart §3.6: every asserted payload is validated
 * against the LIVE `components.schemas.MessageCreatedEvent`/`ChatReadEvent`
 * of `contracts/openapi.yaml` (the nested `Message` `$ref` included) — not a
 * hand-copied field list — plus the strictly-by-contract client script of
 * quickstart §3.6.2 (ensure → subscribe → send → receive → same-id re-POST
 * without a duplicate), driven exclusively through the public operations
 * №11/№15/№16/№17/№18.
 *
 * The raw frames are read through the T002 fixture ([MessagingTestSupport]
 * JDK-HttpClient reader) on purpose: the build carries no webflux dependency
 * (research.md §13 names WebTestClient, but plan.md VII adds no such
 * dependency), and a codec-based client would strip the `retry:`/`:ka`
 * framing that this IT must assert verbatim.
 *
 * NOTE (TDD, constitution VI): written BEFORE T009–T019 — until the chats
 * endpoints and the realtime channel land, every method here fails (RED) by
 * design. SC-005 realtime latency (p95 ≤ 2 s) is the k6 profile's budget
 * (T066, research.md §13); this IT asserts functional delivery within a
 * CI-tolerant deadline instead of a percentile.
 */
@Suppress("TooManyFunctions") // T064: the IT carries the schema validator next to the frame tests it gates
class RealtimeSseIT(
    @Autowired private val chatsProperties: ChatsProperties,
) : MessagingTestSupport() {
    /** First `message.created` frame must arrive well inside this CI-tolerant budget. */
    private val deliveryBudget: Duration = Duration.ofSeconds(5)

    /** realtime-channel.md §1: the stream opens with the reconnect hint `retry: 3000`. */
    @Test
    fun `stream opens with retry 3000 frame`() {
        val user = messagingUser("retry")

        openUserEvents(user).use { stream ->
            val first = stream.nextFrame(FIRST_FRAME_BUDGET)

            assertThat(first)
                .overridingErrorMessage("the first frame of the user event stream must be the retry frame")
                .isNotNull
            assertThat(first!!.retryMillis)
                .overridingErrorMessage("the opening frame must carry SSE field retry: 3000")
                .isEqualTo(RETRY_MILLIS)
            assertThat(first.event)
                .overridingErrorMessage("the retry frame must not carry an event: field")
                .isNull()
            assertThat(first.data)
                .overridingErrorMessage("the retry frame must not carry a data: field")
                .isNull()
        }
    }

    /**
     * realtime-channel.md §1/§2: the server keeps the stream alive with the
     * `:ka` comment frame at the `chats.realtime.heartbeat` cadence (15 s from
     * application.yml / T001) — the first heartbeat arrives within one period
     * (plus slack) of the connect, and consecutive heartbeats keep the period,
     * so idle proxies do not cut the stream (research.md §2 keepalive).
     */
    @Test
    fun `heartbeat ka arrives at the configured cadence`() {
        val heartbeat = chatsProperties.realtime.heartbeat
        assertThat(heartbeat)
            .overridingErrorMessage("chats.realtime.heartbeat must stay at the contract value 15 s (T001)")
            .isEqualTo(Duration.ofSeconds(15))
        val user = messagingUser("ka")

        openUserEvents(user).use { stream ->
            val openedAt = System.nanoTime()
            val firstKaAt = awaitHeartbeat(stream, heartbeat.plusMillis(HEARTBEAT_SLACK_MILLIS))
            val firstGap = Duration.ofNanos(firstKaAt - openedAt)
            assertThat(firstGap)
                .overridingErrorMessage(
                    "the first :ka heartbeat must arrive within one heartbeat period (+%d ms slack), took %s",
                    HEARTBEAT_SLACK_MILLIS,
                    firstGap,
                ).isLessThanOrEqualTo(heartbeat.plusMillis(HEARTBEAT_SLACK_MILLIS))

            val secondKaAt = awaitHeartbeat(stream, heartbeat.plusMillis(HEARTBEAT_SLACK_MILLIS))
            val cadence = Duration.ofNanos(secondKaAt - firstKaAt)
            assertThat(cadence.toMillis())
                .overridingErrorMessage(
                    "consecutive :ka heartbeats must keep the %s cadence (got %s)",
                    heartbeat,
                    cadence,
                ).isBetween((heartbeat.toMillis() / 2), heartbeat.multipliedBy(2).toMillis())
        }
    }

    /**
     * realtime-channel.md §3.1 (FR-007): after a №16 POST both participants
     * receive `message.created` on their own streams — the recipient renders
     * it, the sender's other devices converge (US2-6) — and the payload is the
     * contract `MessageCreatedEvent`: only `chatId` + `message`, the inner
     * `message` matching the `Message` schema and byte-equal to the №16
     * response of the very POST that triggered it.
     */
    @Test
    fun `message created frame reaches both participants with contract payload`() {
        val (alice, bob) = messagingPair()
        val text = "Привет из RealtimeSseIT — US1-3 ✓"

        openUserEvents(alice).use { aliceStream ->
            openUserEvents(bob).use { bobStream ->
                val chatId = ensureChatOk(alice, bob.id)
                val stored = sendMessageOk(alice, chatId, text)

                for ((participant, stream) in listOf(alice to aliceStream, bob to bobStream)) {
                    val event = stream.awaitEvent(MESSAGE_CREATED_EVENT, deliveryBudget)

                    assertConformsToSchema(event, MESSAGE_CREATED_EVENT_SCHEMA)
                    assertThat(fieldNames(event))
                        .overridingErrorMessage(
                            "message.created payload for <%s> must have exactly the MessageCreatedEvent fields",
                            participant.username,
                        ).containsExactlyInAnyOrder("chatId", "message")
                    assertThat(event["chatId"].asText())
                        .overridingErrorMessage("event.chatId must be the dialog UUID")
                        .isEqualTo(chatId.toString())

                    val message = event["message"]
                    assertThat(fieldNames(message))
                        .overridingErrorMessage(
                            "event.message for <%s> must have exactly the Message schema fields",
                            participant.username,
                        ).containsExactlyInAnyOrder("id", "chatId", "senderId", "text", "seq", "createdAt")
                    assertThat(UUID.fromString(message["id"].asText()))
                        .overridingErrorMessage("message.id must be the clientMessageId UUID (FR-004)")
                        .isEqualTo(UUID.fromString(stored["id"].asText()))
                    assertThat(UUID.fromString(message["chatId"].asText()))
                        .overridingErrorMessage("message.chatId must match event.chatId")
                        .isEqualTo(chatId)
                    assertThat(UUID.fromString(message["senderId"].asText()))
                        .overridingErrorMessage("message.senderId must be the sending participant")
                        .isEqualTo(alice.id)
                    assertThat(message["text"].asText())
                        .overridingErrorMessage("message.text must carry the sent text verbatim (FR-003)")
                        .isEqualTo(text)
                    assertThat(message["seq"].asLong())
                        .overridingErrorMessage("message.seq must be a positive server sequence")
                        .isPositive()
                    val createdAtIsInstant =
                        runCatching { Instant.parse(message["createdAt"].asText()).isAfter(Instant.EPOCH) }
                            .getOrDefault(false)
                    assertThat(createdAtIsInstant)
                        .overridingErrorMessage("message.createdAt must be an ISO-8601 instant")
                        .isTrue()
                    assertThat(message)
                        .overridingErrorMessage(
                            "event.message for <%s> must equal the Message returned by POST /chats/{id}/messages",
                            participant.username,
                        ).isEqualTo(stored)
                }
            }
        }
    }

    /**
     * T064 (US6, realtime-channel.md §3.2/FR-010): the №17 read receipt of
     * the recipient surfaces on the SENDER's stream as a `chat.read` frame
     * whose payload validates against the LIVE
     * `components.schemas.ChatReadEvent` of contracts/openapi.yaml — exactly
     * `chatId` + `readUpToSeq` + `byUserId`, `additionalProperties: false`,
     * `readUpToSeq ≥ 1` — and carries the semantics the contract promises:
     * the dialog, the watermark just advanced to the №16 message seq, and
     * the reader (not the sender), so a contract-only sender renders ✓✓
     * (SC-007 path). Monotonic-only publication (no repeat on idempotent
     * re-reads) is covered by ReadReceiptsIT (T041).
     */
    @Test
    fun `chat read frame conforms to ChatReadEvent contract schema`() {
        val (alice, bob) = messagingPair()
        val chatId = ensureChatOk(bob, alice.id)
        val stored = sendMessageOk(bob, chatId, "Прочти меня — §3.2 ✓")
        val readSeq = stored["seq"].asLong()

        openUserEvents(bob).use { stream ->
            val read = markRead(alice, chatId, readSeq)
            assertThat(read.statusCode)
                .overridingErrorMessage(
                    "№17 POST /chats/{id}/read of the recipient must answer 204, got <%s>: %s",
                    read.statusCode,
                    read.body,
                ).isEqualTo(HttpStatus.NO_CONTENT)

            val event = stream.awaitEvent(CHAT_READ_EVENT, deliveryBudget)

            assertConformsToSchema(event, CHAT_READ_EVENT_SCHEMA)
            assertThat(UUID.fromString(event["chatId"].asText()))
                .overridingErrorMessage("chat.read.chatId must be the dialog the receipt belongs to")
                .isEqualTo(chatId)
            assertThat(event["readUpToSeq"].asLong())
                .overridingErrorMessage("chat.read.readUpToSeq must be the watermark just posted (the message seq)")
                .isEqualTo(readSeq)
            assertThat(UUID.fromString(event["byUserId"].asText()))
                .overridingErrorMessage("chat.read.byUserId must be the participant who READ, not the sender")
                .isEqualTo(alice.id)
        }
    }

    /**
     * T064 (US6, quickstart §3.6.2): the strictly-by-contract client script —
     * every step goes through the public operations only (№11 ensure, №18
     * subscribe, №16 send, №15 history; no internal interfaces) — runs the
     * US6 acceptance end to end: ensure → subscribe → send → receive a
     * `message.created` frame whose payload validates against
     * `components.schemas.MessageCreatedEvent` (nested `Message` `$ref`
     * included) and repeats the №16 answer verbatim → re-POST the SAME
     * `clientMessageId` → the contract dedup branch `200` with the very same
     * `Message`, NO duplicate frame on the stream and exactly one instance
     * in the №15 history (FR-004, SC-002 — a contract client never sees a
     * double from its own retries).
     */
    @Test
    fun `strictly contract client script ensure subscribe send receive and same id dedup`() {
        val (alice, bob) = messagingPair()
        val text = "Скрипт строго по контракту — §3.6.2 ✓"
        val clientMessageId = UUID.randomUUID()

        val chatId = ensureChatOk(bob, alice.id)
        openUserEvents(alice).use { stream ->
            val firstPost = sendMessage(bob, chatId, text, clientMessageId)
            assertThat(firstPost.statusCode)
                .overridingErrorMessage(
                    "the first №16 POST of a fresh clientMessageId must answer 201, got <%s>: %s",
                    firstPost.statusCode,
                    firstPost.body,
                ).isEqualTo(HttpStatus.CREATED)
            val stored = contractJson.readTree(firstPost.body)

            val event = stream.awaitEvent(MESSAGE_CREATED_EVENT, deliveryBudget)
            assertConformsToSchema(event, MESSAGE_CREATED_EVENT_SCHEMA)
            assertThat(event["message"])
                .overridingErrorMessage("the received message.created payload must repeat the №16 Message verbatim")
                .isEqualTo(stored)

            val dedupPost = sendMessage(bob, chatId, text, clientMessageId)
            assertThat(dedupPost.statusCode)
                .overridingErrorMessage(
                    "re-POST of the same clientMessageId must take the №16 dedup branch 200, got <%s>: %s",
                    dedupPost.statusCode,
                    dedupPost.body,
                ).isEqualTo(HttpStatus.OK)
            assertThat(contractJson.readTree(dedupPost.body))
                .overridingErrorMessage("the №16 dedup answer must be the same stored Message (FR-004)")
                .isEqualTo(stored)

            assertNoEventFollows(stream, MESSAGE_CREATED_EVENT, NO_DUPLICATE_FRAME_WINDOW)
        }

        val history = listMessages(alice, chatId)
        assertThat(history.statusCode)
            .overridingErrorMessage("№15 GET /chats/{id}/messages must answer 200, got <%s>", history.statusCode)
            .isEqualTo(HttpStatus.OK)
        val page = contractJson.readTree(history.body)
        val instances = page["messages"].filter { it["id"].asText() == clientMessageId.toString() }
        assertThat(instances)
            .overridingErrorMessage(
                "the №15 history must hold EXACTLY ONE instance of the retried clientMessageId (SC-002), page: %s",
                history.body,
            ).hasSize(1)
        assertConformsToSchema(instances[0], MESSAGE_SCHEMA)
    }

    /**
     * Waits for the next pure `:ka` comment frame (realtime-channel.md §2) and
     * returns its arrival time in nanos — skipping `retry:` and event frames,
     * which may interleave legally.
     */
    private fun awaitHeartbeat(
        stream: UserEventsStream,
        timeout: Duration,
    ): Long {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (true) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) {
                throw AssertionError("timed out after $timeout waiting for a :ka heartbeat")
            }
            val frame =
                stream.nextFrame(Duration.ofNanos(remaining))
                    ?: throw AssertionError("stream ended before a :ka heartbeat arrived")
            if (frame.isHeartbeat) {
                assertThat(frame.comment)
                    .overridingErrorMessage("the heartbeat frame must be the :ka comment")
                    .isEqualTo(HEARTBEAT_COMMENT)
                assertThat(frame.event)
                    .overridingErrorMessage("the heartbeat frame must not carry an event: field")
                    .isNull()
                assertThat(frame.data)
                    .overridingErrorMessage("the heartbeat frame must not carry a data: field")
                    .isNull()
                return System.nanoTime()
            }
        }
    }

    private fun fieldNames(node: JsonNode): List<String> = node.fieldNames().asSequence().toList()

    /**
     * T064 (US6): asserts a live SSE payload against the very schema of the
     * public contract — `contracts/openapi.yaml`
     * `components.schemas.<schemaName>` — instead of a hand-copied field
     * list, so a schema change that breaks the wire payload fails here first
     * (constitution IV API-First). The validator covers exactly the JSON
     * Schema subset the event schemas (and the nested `Message` `$ref`) use:
     * `type` (`object`/`string`/`integer`), `required`, `properties`,
     * `additionalProperties: false`, `$ref`, `format` (`uuid`/`date-time`),
     * `maxLength`, `minimum`.
     */
    private fun assertConformsToSchema(
        payload: JsonNode,
        schemaName: String,
    ) {
        val schema = openApiContract["components"]["schemas"][schemaName]
        if (schema !is ObjectNode) {
            throw AssertionError(
                "contracts/openapi.yaml must keep components.schemas.$schemaName — " +
                    "the №18 payloads are declared there (realtime-channel.md §5), got: ${schema.nodeType}",
            )
        }
        val violations = mutableListOf<String>()
        validateAgainst(payload, schema, "#", violations)
        assertThat(violations)
            .overridingErrorMessage(
                "SSE payload violates components.schemas.%s of contracts/openapi.yaml: %s",
                schemaName,
                violations,
            ).isEmpty()
    }

    /**
     * Consumes frames for [window] and fails the moment an `event:` frame of
     * [eventType] shows up — the §3.6.2 "no duplicate" half: the FR-004 dedup
     * re-POST must not publish `message.created` again. A per-frame timeout
     * (no COMPLETE frame within the window) is the expected clean exit.
     */
    private fun assertNoEventFollows(
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
                .overridingErrorMessage(
                    "no further <%s> frame may follow the FR-004 dedup re-POST of the same clientMessageId",
                    eventType,
                ).isNotEqualTo(eventType)
        }
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
        val target = openApiContract.at(ref.removePrefix("#")).takeUnless { it.isMissingNode }
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
                if (runCatching { OffsetDateTime.parse(text) }.isFailure) {
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
        const val RETRY_MILLIS = 3_000L
        const val HEARTBEAT_COMMENT = "ka"
        const val HEARTBEAT_SLACK_MILLIS = 5_000L
        const val MESSAGE_CREATED_EVENT = "message.created"
        const val CHAT_READ_EVENT = "chat.read"
        const val MESSAGE_CREATED_EVENT_SCHEMA = "MessageCreatedEvent"
        const val CHAT_READ_EVENT_SCHEMA = "ChatReadEvent"
        const val MESSAGE_SCHEMA = "Message"
        const val LOCAL_REF_PREFIX = "#/"
        const val TIMEOUT_MARKER = "timed out"
        val FIRST_FRAME_BUDGET: Duration = Duration.ofSeconds(5)

        /** §3.6.2 "no duplicate": any push of a dedup re-POST would land well inside the ≤2 s SC-005 budget. */
        val NO_DUPLICATE_FRAME_WINDOW: Duration = Duration.ofSeconds(3)

        /** `backend/` (Gradle) and the repo root (IDE runs) in order. */
        val CONTRACT_LOCATIONS: List<Path> =
            listOf(Path.of("../contracts/openapi.yaml"), Path.of("contracts/openapi.yaml"))

        val contractJson: ObjectMapper = ObjectMapper()

        /** contracts/openapi.yaml parsed once per JVM (T064). */
        val openApiContract: JsonNode by lazy { loadOpenApiContract() }

        /**
         * The public contract of the monorepo — the single source of truth
         * the №18 payloads are asserted against (T064). Located by walking
         * up from the Gradle working dir (`backend/`) to the repo root;
         * parsed through SnakeYAML (on the classpath via Spring Boot's yaml
         * support) and mapped onto a Jackson tree.
         */
        private fun loadOpenApiContract(): JsonNode {
            val contract =
                CONTRACT_LOCATIONS.firstOrNull { Files.isRegularFile(it) }
                    ?: throw IllegalStateException(
                        "contracts/openapi.yaml not found at ${CONTRACT_LOCATIONS.joinToString()} — " +
                            "the T064 conformance check must read the live public contract",
                    )
            return contract.toFile().inputStream().use { input ->
                val yamlRoot: Any? = Yaml().load(input)
                contractJson.valueToTree<JsonNode>(yamlRoot)
            }
        }
    }
}
