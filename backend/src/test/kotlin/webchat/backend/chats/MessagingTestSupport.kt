package webchat.backend.chats

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.util.UriComponentsBuilder
import webchat.backend.AbstractIntegrationTest
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Optional
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * T002 (tasks.md Phase 1): shared fixtures of the 004 messaging ITs — used by
 * T007 (RealtimeSseIT), T008 (ChatAccessIT), T008a (MessageValidationIT),
 * T029 (MessagingDeliveryIT) and the later story ITs — on top of
 * [AbstractIntegrationTest] (Testcontainers PG 17 + Redis 7, real HTTP on a
 * random port).
 *
 * Covers the preparation side of the acceptance scenarios: complete
 * registration and login of two or more distinct users through the real 001
 * flows (register → verification letter from the email outbox table → confirm
 * → set password → login → Bearer access token), unique per call so no test
 * method shares account rows or the Redis rate-limit buckets; chat creation
 * (contract №11 POST /chats/ensure), message sending (№16), history reads
 * (№15) and read receipts (№17); and a raw SSE reader for the user event
 * stream (№18 GET /users/me/events).
 *
 * The SSE reader is built on the JDK HttpClient deliberately: this build
 * carries no webflux dependency (plan.md adds none — VII), and T007 must
 * assert the raw WHATWG framing of realtime-channel.md §2 (`retry: 3000`,
 * heartbeat `:ka`, `event:`/`data:` frames), which a codec-based client
 * would strip.
 */
@Suppress("TooManyFunctions") // T002: fixture library — one helper per contract operation №11–№18
abstract class MessagingTestSupport : AbstractIntegrationTest() {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @LocalServerPort
    private var port: Int = 0

    /** A fully registered and logged-in fixture user (contract №5 TokenPair owner). */
    protected data class MessagingUser(
        val id: UUID,
        val username: String,
        val email: String,
        val accessToken: String,
        val refreshToken: String,
    )

    /** One parsed SSE frame exactly as framed on the wire (realtime-channel.md §2). */
    protected data class SseFrame(
        val event: String? = null,
        val data: String? = null,
        val retryMillis: Long? = null,
        val comment: String? = null,
    ) {
        val isHeartbeat: Boolean
            get() = event == null && data == null && comment != null
    }

    /**
     * Registers and logs in a fresh user (US1 fixture basis). Every call
     * produces a unique username/email AND a unique fixture source IP from
     * the dedicated 203.0.114.0/24 block, so callers never collide on
     * unique rows or the register/login Redis buckets (5/1h per IP, 10/1m
     * per IP — application.yml auth.ratelimit). The dedicated block matters
     * for the full `gradlew check` run: the generic per-request rotator
     * below spans 203.0.113.149–.252, which overlaps the SSO suites' owned
     * TEST-NET-3 blocks (SsoResilienceIT .180+, SsoOauth2FlowIT .220+)
     * that also spend real register buckets against the shared static
     * Redis — a fixture landing there mid-suite got 429. The optional
     * [email] override (T047) keeps the username random while pinning the
     * address — the case-insensitive sort fixtures need an email order
     * that differs from the username order.
     */
    protected fun messagingUser(
        label: String,
        email: String? = null,
    ): MessagingUser {
        val username = "$label-${UUID.randomUUID().toString().substring(0, UUID_SUFFIX_LENGTH)}"
        val userEmail = email ?: "$username@example.com"
        val sourceIp = fixtureSourceIp()

        val registration =
            postJson(
                REGISTER_PATH,
                mapOf("username" to username, "email" to userEmail),
                sourceIp = sourceIp,
            )
        assertThat(registration.statusCode)
            .overridingErrorMessage("registration of fixture user <%s> must be accepted", username)
            .isEqualTo(HttpStatus.ACCEPTED)

        val confirmation =
            postJson(
                CONFIRM_PATH,
                mapOf("token" to extractVerificationToken(userEmail)),
                sourceIp = sourceIp,
            )
        assertThat(confirmation.statusCode)
            .overridingErrorMessage("email confirmation for fixture user <%s> must succeed", username)
            .isEqualTo(HttpStatus.OK)
        val setupToken = objectMapper.readTree(confirmation.body)["setupToken"].asText()

        val passwordSetup =
            postJson(
                SET_PASSWORD_PATH,
                mapOf(
                    "setupToken" to setupToken,
                    "password" to FIXTURE_PASSWORD,
                    "confirmPassword" to FIXTURE_PASSWORD,
                ),
                sourceIp = sourceIp,
            )
        assertThat(passwordSetup.statusCode)
            .overridingErrorMessage("password setup for fixture user <%s> must succeed", username)
            .isEqualTo(HttpStatus.NO_CONTENT)

        return loginUser(username, sourceIp)
    }

    /** US1 fixture: the two dialog participants of one personal chat. */
    protected fun messagingPair(): Pair<MessagingUser, MessagingUser> = messagingUser("alice") to messagingUser("bob")

    /** FR-002 fixture: a registered user outside the chat (Carol in the IT scenarios). */
    protected fun strangerUser(): MessagingUser = messagingUser("carol")

    /** Contract №11 POST /chats/ensure — raw response for status/error assertions. */
    protected fun ensureChat(
        user: MessagingUser,
        peerUserId: UUID,
    ): ResponseEntity<String> = postJson(ENSURE_CHAT_PATH, mapOf("peerUserId" to peerUserId.toString()), user)

    /** Contract №11 happy path → chatId of the ensured (created or existing) dialog. */
    protected fun ensureChatOk(
        user: MessagingUser,
        peerUserId: UUID,
    ): UUID {
        val response = ensureChat(user, peerUserId)
        assertThat(response.statusCode.is2xxSuccessful)
            .overridingErrorMessage(
                "chats/ensure for user <%s> and peer <%s> must return 200/201, got <%s>: %s",
                user.username,
                peerUserId,
                response.statusCode,
                response.body,
            ).isTrue
        return UUID.fromString(objectMapper.readTree(response.body)["chatId"].asText())
    }

    /** Contract №12 GET /chats — raw response (the caller's dialog list). */
    protected fun listChats(user: MessagingUser): ResponseEntity<String> =
        exchangeWithAuth(
            HttpMethod.GET,
            "/api/v1/chats",
            user,
        )

    /** Contract №13 GET /chats/{chatId} — raw response. */
    protected fun getChat(
        user: MessagingUser,
        chatId: UUID,
    ): ResponseEntity<String> = exchangeWithAuth(HttpMethod.GET, "/api/v1/chats/$chatId", user)

    /** Contract №14 DELETE /chats/{chatId} — raw response. */
    protected fun deleteChat(
        user: MessagingUser,
        chatId: UUID,
    ): ResponseEntity<String> = exchangeWithAuth(HttpMethod.DELETE, "/api/v1/chats/$chatId", user)

    /**
     * Contract №16 POST /chats/{chatId}/messages — raw response: 201 on the
     * first insert, 200 on a duplicate `clientMessageId` (FR-004 dedup).
     */
    protected fun sendMessage(
        user: MessagingUser,
        chatId: UUID,
        text: String,
        clientMessageId: UUID = UUID.randomUUID(),
    ): ResponseEntity<String> =
        postJson(
            "/api/v1/chats/$chatId/messages",
            mapOf(
                "clientMessageId" to clientMessageId.toString(),
                "text" to text,
            ),
            user,
        )

    /** Contract №16 happy path → the stored `Message` body (same record on dedup). */
    protected fun sendMessageOk(
        user: MessagingUser,
        chatId: UUID,
        text: String,
    ): JsonNode {
        val response = sendMessage(user, chatId, text)
        assertThat(response.statusCode.is2xxSuccessful)
            .overridingErrorMessage(
                "send from <%s> in chat <%s> must return 200/201, got <%s>: %s",
                user.username,
                chatId,
                response.statusCode,
                response.body,
            ).isTrue
        return objectMapper.readTree(response.body)
    }

    /** Contract №15 GET /chats/{chatId}/messages — raw response (cursor pagination). */
    protected fun listMessages(
        user: MessagingUser,
        chatId: UUID,
        before: Long? = null,
        limit: Int? = null,
    ): ResponseEntity<String> {
        val path =
            UriComponentsBuilder
                .fromPath("/api/v1/chats/$chatId/messages")
                .queryParamIfPresent("before", Optional.ofNullable(before))
                .queryParamIfPresent("limit", Optional.ofNullable(limit))
                .build()
                .toUriString()
        return exchangeWithAuth(HttpMethod.GET, path, user)
    }

    /** Contract №17 POST /chats/{chatId}/read — raw response (idempotent watermark advance). */
    protected fun markRead(
        user: MessagingUser,
        chatId: UUID,
        upToSeq: Long,
    ): ResponseEntity<String> = postJson("/api/v1/chats/$chatId/read", mapOf("upToSeq" to upToSeq), user)

    /** Contract №18: opens the user event stream and starts buffering its frames. */
    protected fun openUserEvents(user: MessagingUser): UserEventsStream = UserEventsStream(port, user, objectMapper)

    private fun loginUser(
        username: String,
        sourceIp: String,
    ): MessagingUser {
        val response =
            postJson(
                LOGIN_PATH,
                mapOf("identifier" to username, "password" to FIXTURE_PASSWORD),
                sourceIp = sourceIp,
            )
        assertThat(response.statusCode)
            .overridingErrorMessage("fixture user <%s> must log in", username)
            .isEqualTo(HttpStatus.OK)
        val body = objectMapper.readTree(response.body)
        return MessagingUser(
            id = UUID.fromString(body["user"]["id"].asText()),
            username = body["user"]["username"].asText(),
            email = body["user"]["email"].asText(),
            accessToken = body["accessToken"].asText(),
            refreshToken = body["refreshToken"].asText(),
        )
    }

    private fun extractVerificationToken(email: String): String {
        val payloads =
            jdbcTemplate.queryForList(
                """
                SELECT payload::text FROM email_outbox
                WHERE lower(recipient_email) = ? AND email_type = 'email_verification'
                ORDER BY created_at DESC
                """.trimIndent(),
                String::class.java,
                email.lowercase(),
            )
        val match = Regex("""token=([A-Za-z0-9_-]{43})""").find(payloads.firstOrNull() ?: "")
        assertThat(match)
            .overridingErrorMessage(
                "verification email to <%s> must contain a /confirm-registration?token=... link",
                email,
            ).isNotNull
        return match!!.groupValues[1]
    }

    private fun postJson(
        path: String,
        payload: Map<String, Any>,
        authenticated: MessagingUser? = null,
        sourceIp: String? = null,
    ): ResponseEntity<String> {
        val headers =
            HttpHeaders().apply {
                contentType = MediaType.APPLICATION_JSON
                set(X_FORWARDED_FOR_HEADER, sourceIp ?: uniqueSourceIp())
                authenticated?.let { setBearerAuth(it.accessToken) }
            }
        return restTemplate.postForEntity(path, HttpEntity(payload, headers), String::class.java)
    }

    private fun exchangeWithAuth(
        method: HttpMethod,
        path: String,
        user: MessagingUser,
    ): ResponseEntity<String> =
        restTemplate.exchange(
            path,
            method,
            HttpEntity<Void>(null, HttpHeaders().apply { setBearerAuth(user.accessToken) }),
            String::class.java,
        )

    /**
     * Raw SSE reader over `GET /api/v1/users/me/events`: delivers every frame
     * of realtime-channel.md §2 as a parsed [SseFrame] — including the opening
     * `retry:` frame, `event:`/`data:` event frames and `:ka` heartbeat
     * comments — with deadline-based waits suited for the ≤2 s SC-005/SC-007
     * assertions of T007.
     */
    protected class UserEventsStream(
        port: Int,
        user: MessagingUser,
        private val objectMapper: ObjectMapper,
    ) : AutoCloseable {
        private val httpClient =
            HttpClient
                .newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
                .build()

        private val pendingLines = ArrayBlockingQueue<String>(QUEUE_CAPACITY)

        private val reader =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "sse-it-reader").apply { isDaemon = true }
            }

        @Volatile
        private var closed = false

        @Volatile
        private var streamFailure: Exception? = null

        init {
            val request =
                HttpRequest
                    .newBuilder()
                    .uri(URI.create("http://localhost:$port$USER_EVENTS_PATH"))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${user.accessToken}")
                    .header(HttpHeaders.ACCEPT, "text/event-stream")
                    .GET()
                    .build()
            val response =
                httpClient
                    .sendAsync(request, HttpResponse.BodyHandlers.ofLines())
                    .get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            assertThat(response.statusCode())
                .overridingErrorMessage("user event stream must open with 200, got <%s>", response.statusCode())
                .isEqualTo(HttpStatus.OK.value())
            assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .overridingErrorMessage("user event stream must answer text/event-stream")
                .contains("text/event-stream")
            reader.execute {
                try {
                    response.body().use { lines -> lines.forEach(pendingLines::put) }
                } catch (failure: Exception) {
                    streamFailure = failure
                } finally {
                    pendingLines.put(STREAM_END)
                }
            }
        }

        /**
         * Blocks until one complete frame arrives (blank-line dispatch) and
         * returns it; `null` only when the server ended the stream. Comment-only
         * (`:ka`) and `retry:`-only frames are returned as frames — T007 asserts
         * them verbatim.
         */
        fun nextFrame(timeout: Duration): SseFrame? {
            var event: String? = null
            var data: String? = null
            var retryMillis: Long? = null
            var comment: String? = null
            val deadline = System.nanoTime() + timeout.toNanos()
            while (true) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) {
                    throw AssertionError("timed out after $timeout waiting for a complete SSE frame")
                }
                val line = pollLine(remaining) ?: return null
                when {
                    line.isEmpty() -> {
                        val frame = SseFrame(event, data, retryMillis, comment)
                        if (frame != SseFrame()) return frame
                    }
                    line.startsWith(":") -> comment = line.removePrefix(":").trim()
                    line.startsWith("event:") -> event = fieldValue(line)
                    line.startsWith("data:") ->
                        data =
                            if (data == null) {
                                fieldValue(line)
                            } else {
                                data + "\n" + fieldValue(line)
                            }
                    line.startsWith("retry:") -> retryMillis = fieldValue(line).toLongOrNull()
                }
            }
        }

        /**
         * Waits for the next `event:` frame of [eventType] and returns its JSON
         * payload; unknown event types are skipped (forward compatibility,
         * US6-3). Realtime push budgets are asserted through the caller-chosen
         * [timeout].
         */
        fun awaitEvent(
            eventType: String,
            timeout: Duration,
        ): JsonNode {
            val deadline = System.nanoTime() + timeout.toNanos()
            while (true) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) {
                    throw AssertionError("timed out after $timeout waiting for SSE event <$eventType>")
                }
                val frame =
                    nextFrame(Duration.ofNanos(remaining))
                        ?: throw AssertionError("stream ended before <$eventType> arrived")
                if (frame.event == eventType) {
                    assertThat(frame.data)
                        .overridingErrorMessage("SSE event <$eventType> must carry a data payload")
                        .isNotNull
                    return objectMapper.readTree(frame.data)
                }
            }
        }

        override fun close() {
            closed = true
            reader.shutdownNow()
            httpClient.close()
        }

        private fun pollLine(timeoutNanos: Long): String? =
            when (val line = pendingLines.poll(timeoutNanos, TimeUnit.NANOSECONDS)) {
                null ->
                    throw AssertionError(
                        streamFailure?.let { failure -> "SSE stream failed while waiting for a line: $failure" }
                            ?: "timed out waiting for an SSE line",
                    )
                STREAM_END -> {
                    if (!closed && streamFailure != null) {
                        throw AssertionError("SSE stream terminated by an error: $streamFailure")
                    }
                    null
                }
                else -> line.removeSuffix(CARRIAGE_RETURN)
            }

        private fun fieldValue(line: String): String = line.substringAfter(":").removePrefix(" ")

        private companion object {
            const val CARRIAGE_RETURN = "\r"
        }
    }

    private companion object {
        const val FIXTURE_PASSWORD = "Msg-IT-004-Fixture-Pass!"
        const val UUID_SUFFIX_LENGTH = 8
        const val QUEUE_CAPACITY = 1024
        const val CONNECT_TIMEOUT_SECONDS = 10L
        const val STREAM_END = "\u0000stream-end"
        const val X_FORWARDED_FOR_HEADER = "X-Forwarded-For"
        const val REGISTER_PATH = "/api/v1/auth/register"
        const val CONFIRM_PATH = "/api/v1/auth/register/confirm"
        const val SET_PASSWORD_PATH = "/api/v1/auth/register/password"
        const val LOGIN_PATH = "/api/v1/auth/login"
        const val ENSURE_CHAT_PATH = "/api/v1/chats/ensure"
        const val USER_EVENTS_PATH = "/api/v1/users/me/events"

        const val SOURCE_IP_LAST_OCTET_BASE = 149L
        const val SOURCE_IP_LAST_OCTET_SPAN = 104L
        val sourceIpCounter = AtomicLong()

        // Dedicated 203.0.114.0/24 block for fixture ACCOUNT lifecycles
        // (register/confirm/password/login): never overlaps the generic
        // rotator above nor the SSO suites' 203.0.113.x blocks, so the
        // shared-Redis register buckets of the full run cannot collide.
        const val FIXTURE_IP_PREFIX = "203.0.114."
        const val FIXTURE_IP_LAST_OCTET_SPAN = 254L
        val fixtureIpCounter = AtomicLong()

        fun fixtureSourceIp(): String =
            FIXTURE_IP_PREFIX +
                (1L + fixtureIpCounter.incrementAndGet() % FIXTURE_IP_LAST_OCTET_SPAN)

        fun uniqueSourceIp(): String {
            val lastOctet = SOURCE_IP_LAST_OCTET_BASE + sourceIpCounter.incrementAndGet() % SOURCE_IP_LAST_OCTET_SPAN
            return "203.0.113.$lastOctet"
        }
    }
}
