package webchat.backend.email

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import webchat.backend.AbstractIntegrationTest
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Controllable in-process double of [EmailGateway] (T050; research.md §6
 * "fake EmailGateway", quickstart §4.6 without a socket).
 *
 * Starts in the failing mode and counts every send invocation per recipient,
 * so retry/backoff bookkeeping is observable without real SMTP. The failure
 * message is deliberately secret-free: whatever reaches `last_error` through
 * [OutboxPoller.errorDescription] must stay clean of credentials and raw PII
 * (SC-005, SC-007).
 */
class FakeEmailGateway : EmailGateway {
    @Volatile var failing: Boolean = true
    val attemptedRecipients: MutableList<String> = CopyOnWriteArrayList()
    val delivered: MutableList<EmailMessage> = CopyOnWriteArrayList()

    override fun send(message: EmailMessage) {
        attemptedRecipients.add(message.recipient)
        if (failing) {
            error("fake SMTP endpoint unreachable: connection refused (simulated outage)")
        }
        delivered.add(message)
    }

    fun reset(failing: Boolean) {
        this.failing = failing
        attemptedRecipients.clear()
        delivered.clear()
    }

    // recipient values come straight from the stored rows, which the tests
    // always seed lowercase — a strict comparison is enough here
    fun attemptsFor(recipientEmail: String): Int = attemptedRecipients.count { it == recipientEmail }

    fun deliveredTo(recipientEmail: String): List<EmailMessage> = delivered.filter { it.recipient == recipientEmail }
}

/**
 * US6-1…US6-3, SC-007 (T050): email delivery resilience over the transactional
 * outbox — this IT replaces the real SMTP adapter with the fake above
 * (`@Primary` wins over [SmtpEmailGateway] in this context only).
 *
 * Covers: a failing gateway never surfaces on the public endpoint —
 * POST /api/v1/auth/register still answers 202 while the letter stays queued
 * `pending` with a future `next_attempt_at` (backoff gate: a dispatch run
 * before the retry is due claims nothing) and a secret-free `last_error`
 * (US6-1, SC-007); after the gateway recovers the very same row is delivered
 * — `sent` + `sent_at`, error cleared, letter carries the original
 * /confirm-registration?token=... link (US6-2); a row that exhausts 10 failed
 * attempts is parked as `failed_permanent` and never claimed again (US6-3 —
 * the full unified schedule lands with T052, so this method is the RED part
 * of the TDD cycle until then); captured logs and stored errors contain no
 * passwords, open one-time token values or raw recipient PII (SC-007).
 *
 * Determinism: the 5 s scheduler tick is disabled for this context
 * (`auth.outbox.poll-fixed-delay=1h`; the unavoidable immediate startup tick
 * sees only foreign rows, and the fake starts failing), leftover `pending`
 * rows of other IT classes are parked at `next_attempt_at = infinity`, and
 * every retry cycle is driven synchronously — `next_attempt_at = now()` +
 * [OutboxPoller.dispatchPending] — instead of waiting out the real 60 s+
 * backoff intervals.
 */
@ExtendWith(OutputCaptureExtension::class)
class EmailOutboxResilienceIT(
    @Autowired private val restTemplate: TestRestTemplate,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val outboxPoller: OutboxPoller,
    @Autowired private val fakeEmailGateway: FakeEmailGateway,
) : AbstractIntegrationTest() {
    @TestConfiguration
    class FakeGatewayConfig {
        @Bean
        @Primary
        fun fakeEmailGateway(): FakeEmailGateway = FakeEmailGateway()
    }

    @Test
    fun `gateway outage keeps register at 202 and the letter queued with backoff`() {
        fakeEmailGateway.reset(failing = true)
        parkForeignPendingRows()

        val response = register("outageolga", "outage-olga@example.com")

        // SC-007: the outage is invisible to the caller — accepted, never 5xx
        assertThat(response.statusCode).isEqualTo(HttpStatus.ACCEPTED)

        var row = outboxRow("outage-olga@example.com")!!
        assertThat(row["status"]).isEqualTo("pending")
        assertThat(row["attempts"]).isEqualTo(0)

        runDispatchCycle("outage-olga@example.com")

        row = outboxRow("outage-olga@example.com")!!
        assertThat(row["status"]).isEqualTo("pending")
        assertThat(row["attempts"]).isEqualTo(1)
        assertThat(row["last_error"] as String?)
            .isNotNull()
            .contains("fake SMTP endpoint unreachable")
            .doesNotContain("outage-olga@example.com")
            .doesNotContain("token=")
        assertThat(nextAttemptIsInFuture("outage-olga@example.com")).isTrue

        // backoff gate: a dispatch run before next_attempt_at claims nothing
        outboxPoller.dispatchPending()
        assertThat(outboxRow("outage-olga@example.com")!!["attempts"]).isEqualTo(1)
        assertThat(fakeEmailGateway.attemptsFor("outage-olga@example.com")).isEqualTo(1)
        assertThat(fakeEmailGateway.deliveredTo("outage-olga@example.com")).isEmpty()
    }

    @Test
    fun `recovered gateway delivers the queued letter with the original one-time link`() {
        fakeEmailGateway.reset(failing = true)
        parkForeignPendingRows()

        assertThat(register("recoverraj", "recover-raj@example.com").statusCode)
            .isEqualTo(HttpStatus.ACCEPTED)
        val openToken = extractOpenToken("recover-raj@example.com")

        runDispatchCycle("recover-raj@example.com")
        assertThat(outboxRow("recover-raj@example.com")!!["attempts"]).isEqualTo(1)

        // the provider comes back; the same row must go out on its next due slot
        fakeEmailGateway.failing = false
        runDispatchCycle("recover-raj@example.com")

        val row = outboxRow("recover-raj@example.com")!!
        assertThat(row["status"]).isEqualTo("sent")
        assertThat(row["sent_at"]).isNotNull
        assertThat(row["last_error"]).isNull()
        assertThat(row["attempts"]).isEqualTo(2)

        val letters = fakeEmailGateway.deliveredTo("recover-raj@example.com")
        assertThat(letters).hasSize(1)
        assertThat(letters.single().recipient).isEqualTo("recover-raj@example.com")
        assertThat(letters.single().subject).contains("Webchat")
        assertThat(letters.single().text).contains("/confirm-registration?token=$openToken")
        assertThat(fakeEmailGateway.attemptsFor("recover-raj@example.com")).isEqualTo(2)
    }

    @Test
    fun `ten failed attempts park the row as failed_permanent and stop claiming it`() {
        fakeEmailGateway.reset(failing = true)
        parkForeignPendingRows()

        assertThat(register("permfailpat", "perm-fail-pat@example.com").statusCode)
            .isEqualTo(HttpStatus.ACCEPTED)

        repeat(10) { runDispatchCycle("perm-fail-pat@example.com") }

        val row = outboxRow("perm-fail-pat@example.com")!!
        assertThat(row["attempts"]).isEqualTo(10)
        assertThat(row["status"]).isEqualTo("failed_permanent")
        assertThat(row["last_error"] as String?)
            .isNotNull()
            .doesNotContain("perm-fail-pat@example.com")
            .doesNotContain("token=")
        assertThat(fakeEmailGateway.attemptsFor("perm-fail-pat@example.com")).isEqualTo(10)
        assertThat(fakeEmailGateway.deliveredTo("perm-fail-pat@example.com")).isEmpty()

        // parked permanently: further cycles neither claim the row nor retry it
        runDispatchCycle("perm-fail-pat@example.com")
        assertThat(fakeEmailGateway.attemptsFor("perm-fail-pat@example.com")).isEqualTo(10)
    }

    @Test
    fun `logs and stored errors carry no open tokens or raw recipient pii`(capturedOutput: CapturedOutput) {
        fakeEmailGateway.reset(failing = true)
        parkForeignPendingRows()

        assertThat(register("quietquinn", "quiet-quinn@example.com").statusCode)
            .isEqualTo(HttpStatus.ACCEPTED)
        val openToken = extractOpenToken("quiet-quinn@example.com")

        runDispatchCycle("quiet-quinn@example.com")

        // stored error exists only while the row is failing — check it before
        // the recovery cycle clears it (MARK_SENT resets last_error to NULL)
        val storedError = outboxRow("quiet-quinn@example.com")!!["last_error"] as String?
        assertThat(storedError)
            .isNotNull()
            .doesNotContain(openToken)
            .doesNotContain("quiet-quinn@example.com")

        fakeEmailGateway.failing = false
        runDispatchCycle("quiet-quinn@example.com")
        assertThat(fakeEmailGateway.deliveredTo("quiet-quinn@example.com")).hasSize(1)

        // SC-007: observability sees the failure and the recovery, never the
        // letter contents — no open one-time token, no raw recipient/username
        assertThat(capturedOutput.all)
            .doesNotContain(openToken)
            .doesNotContain("quiet-quinn@example.com")
            .doesNotContain("quietquinn")
            .doesNotContain("token=")
    }

    private fun register(
        username: String,
        email: String,
    ): ResponseEntity<String> {
        val headers =
            HttpHeaders().apply {
                contentType = MediaType.APPLICATION_JSON
                set("X-Forwarded-For", uniqueSourceIp())
            }
        return restTemplate.postForEntity(
            REGISTER_PATH,
            HttpEntity(mapOf("username" to username, "email" to email), headers),
            String::class.java,
        )
    }

    /** One retry cycle: force the row due, then run a synchronous dispatcher tick. */
    private fun runDispatchCycle(recipientEmail: String) {
        jdbcTemplate.update(
            """
            UPDATE email_outbox SET next_attempt_at = now()
            WHERE lower(recipient_email) = ? AND status = 'pending'
            """.trimIndent(),
            recipientEmail.lowercase(),
        )
        outboxPoller.dispatchPending()
    }

    /** Neutralizes leftover pending rows of other IT classes (shared static PG). */
    private fun parkForeignPendingRows() {
        jdbcTemplate.update(
            """
            UPDATE email_outbox SET next_attempt_at = 'infinity'
            WHERE status = 'pending' AND next_attempt_at <> 'infinity'
            """.trimIndent(),
        )
    }

    private fun outboxRow(recipientEmail: String): Map<String, Any?>? {
        val rows =
            jdbcTemplate.queryForList(
                """
                SELECT status::text AS status, attempts, last_error, sent_at,
                       payload::text AS payload
                FROM email_outbox
                WHERE lower(recipient_email) = ?
                ORDER BY created_at DESC
                """.trimIndent(),
                recipientEmail.lowercase(),
            )
        return rows.firstOrNull()
    }

    private fun nextAttemptIsInFuture(recipientEmail: String): Boolean {
        val isFuture: Boolean? =
            jdbcTemplate.queryForObject(
                """
                SELECT next_attempt_at > now() FROM email_outbox
                WHERE lower(recipient_email) = ?
                """.trimIndent(),
                Boolean::class.javaObjectType,
                recipientEmail.lowercase(),
            )
        return isFuture == true
    }

    private fun extractOpenToken(recipientEmail: String): String {
        val payload = outboxRow(recipientEmail)?.get("payload") as String?
        val match = Regex("""token=([A-Za-z0-9_-]{43})""").find(payload ?: "")
        assertThat(match)
            .overridingErrorMessage(
                "queued letter to <%s> must carry a /confirm-registration?token=... link",
                recipientEmail,
            ).isNotNull
        return match!!.groupValues[1]
    }

    companion object {
        @DynamicPropertySource
        @JvmStatic
        fun outboxResilienceProperties(registry: DynamicPropertyRegistry) {
            // Determinism (see class KDoc): attempts are driven by explicit
            // dispatch cycles, not by the 5 s background tick.
            registry.add("auth.outbox.poll-fixed-delay") { "1h" }
        }

        private const val REGISTER_PATH = "/api/v1/auth/register"

        // 203.0.113.0/24 (TEST-NET-3): fresh address per call keeps every
        // register its own rate-limit bucket on the shared static Redis
        private var sourceIpCounter = 113

        private fun uniqueSourceIp(): String = "203.0.113.${sourceIpCounter++}"
    }
}
