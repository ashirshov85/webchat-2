package webchat.backend.email.templates

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import webchat.backend.email.EmailType

/**
 * T020 unit coverage for the letter/link source used by both outbox sides:
 * the enqueue-time payload (T022b) and the poller render (T021). The link
 * shape (`/confirm-registration?token=...`, `/set-password?token=...`) is
 * the GREEN criterion of T013a/T013c; the IT asserts it end-to-end through
 * the stored payload.
 */
class EmailTemplatesTest {
    private val templates = EmailTemplates("http://localhost:5173")
    private val token = "t".repeat(43) // 256-bit base64url, SC-005: never logged

    @Test
    fun `verification family links to the confirm-registration SPA route`() {
        val first = templates.payload(EmailType.EMAIL_VERIFICATION, "alice", token)
        val repeat = templates.payload(EmailType.EMAIL_VERIFICATION_REPEAT, "alice", token)

        assertThat(first["link"]).isEqualTo("http://localhost:5173/confirm-registration?token=$token")
        assertThat(repeat["link"]).isEqualTo("http://localhost:5173/confirm-registration?token=$token")
        assertThat(first["username"]).isEqualTo("alice")
        assertThat(first["base_url"]).isEqualTo("http://localhost:5173")
    }

    @Test
    fun `password setup links to the set-password SPA route`() {
        val payload = templates.payload(EmailType.PASSWORD_SETUP, "bob", token)

        assertThat(payload["link"]).isEqualTo("http://localhost:5173/set-password?token=$token")
    }

    @Test
    fun `render reproduces the stored link and username in the letter`() {
        val payload = templates.payload(EmailType.EMAIL_VERIFICATION, "alice", token)

        val rendered = templates.render(EmailType.EMAIL_VERIFICATION, payload)

        assertThat(rendered.subject).isNotBlank()
        assertThat(rendered.text).contains("alice")
        assertThat(rendered.text).contains("http://localhost:5173/confirm-registration?token=$token")
    }

    @Test
    fun `repeat and setup letters render with distinct wording`() {
        val verification = templates.render(EmailType.EMAIL_VERIFICATION, mapOf("username" to "u", "link" to "l"))
        val repeat = templates.render(EmailType.EMAIL_VERIFICATION_REPEAT, mapOf("username" to "u", "link" to "l"))
        val setup = templates.render(EmailType.PASSWORD_SETUP, mapOf("username" to "u", "link" to "l"))

        assertThat(verification.subject).isNotEqualTo(repeat.subject)
        assertThat(setup.subject).contains("пароль")
        assertThat(setup.text).contains("l")
    }

    @Test
    fun `render fails fast when a render key is missing`() {
        assertThatThrownBy {
            templates.render(EmailType.PASSWORD_SETUP, mapOf("username" to "bob"))
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("link")
    }

    @Test
    fun `password reset template is not defined until T043`() {
        assertThatThrownBy { templates.payload(EmailType.PASSWORD_RESET, "carol", token) }
            .isInstanceOf(IllegalStateException::class.java)
    }
}
