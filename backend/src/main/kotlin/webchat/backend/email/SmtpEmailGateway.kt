package webchat.backend.email

import org.springframework.beans.factory.annotation.Value
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.stereotype.Component

/**
 * SMTP adapter over `spring-boot-starter-mail` (research.md §6): dev points
 * `SPRING_MAIL_HOST/PORT` at Mailpit, prod at a provider SMTP endpoint from
 * env/K8s Secret. Plain-text messages only (VII: no HTML templates yet).
 * Never logs message content (SC-005); delivery failures propagate to the
 * outbox poller which applies the backoff schedule (SC-007).
 */
@Component
class SmtpEmailGateway(
    private val mailSender: JavaMailSender,
    @param:Value("\${auth.mail.from}") private val fromAddress: String,
) : EmailGateway {
    override fun send(message: EmailMessage) {
        val mail = SimpleMailMessage()
        mail.setFrom(fromAddress)
        mail.setTo(message.recipient)
        mail.subject = message.subject
        mail.text = message.text
        mailSender.send(mail)
    }
}
