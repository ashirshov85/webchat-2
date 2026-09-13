package webchat.backend.email

/**
 * A fully rendered transactional email handed to the delivery gateway.
 *
 * The outbox poller renders templates from the stored payload and passes the
 * result here; the gateway stays free of email-type and template concerns
 * (research.md §6).
 */
data class EmailMessage(
    val recipient: String,
    val subject: String,
    val text: String,
)

/**
 * Domain port for transactional email delivery (DIP, research.md §6, FR-008).
 *
 * One SMTP adapter exists today ([SmtpEmailGateway]: dev — Mailpit, prod —
 * any provider SMTP endpoint via env/K8s Secret); HTTP-API adapters are added
 * later as new implementations without touching the core.
 *
 * Contract: implementations deliver synchronously and signal failure by
 * throwing — the outbox poller turns exceptions into retry/backoff
 * bookkeeping so a gateway outage never surfaces as 5xx on public endpoints
 * (SC-007). Implementations must never log message bodies: they carry open
 * one-time tokens (SC-005).
 */
interface EmailGateway {
    fun send(message: EmailMessage)
}
