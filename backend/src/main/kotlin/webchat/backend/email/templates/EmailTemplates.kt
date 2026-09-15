package webchat.backend.email.templates

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import webchat.backend.email.EmailType

/**
 * A rendered letter: subject and plain-text body (VII: no HTML yet). The
 * outbox poller (T021) combines this with the recipient from the outbox row
 * into an [webchat.backend.email.EmailMessage]; the gateway stays template-free.
 */
data class RenderedEmail(
    val subject: String,
    val text: String,
)

/**
 * Single source of letter texts and SPA links for transactional emails
 * (T020; api-contract.md §1).
 *
 * Links point at SPA routes, never at API endpoints:
 * `{APP_PUBLIC_BASE_URL}/confirm-registration?token=...` for the verification
 * family, `{APP_PUBLIC_BASE_URL}/set-password?token=...` for password setup
 * and `{APP_PUBLIC_BASE_URL}/reset-password?token=...` for password reset
 * (T043, api-contract.md §1).
 *
 * Two consumers share this one type so links and wording can never drift:
 * enqueue side builds the outbox payload ([payload], inside the caller's
 * transaction, T022b), the poller renders the letter back from the stored
 * payload ([render], T021).
 *
 * Security contract (SC-005): payloads and rendered texts carry the open
 * one-time token value and must never be logged; only the gateway delivery
 * is observable.
 */
@Component
class EmailTemplates(
    @param:Value("\${app.public-base-url}") private val publicBaseUrl: String,
) {
    /**
     * Render parameters stored in `email_outbox.payload` (data-model.md §5):
     * the ready link with the open token value, the username, and the base
     * URL the link was built from. The link is embedded at enqueue time —
     * the open token exists only here and in the letter, never in a column.
     */
    fun payload(
        type: EmailType,
        username: String,
        token: String,
    ): Map<String, String> =
        mapOf(
            "username" to username,
            "base_url" to publicBaseUrl,
            "link" to spaLink(type, token),
        )

    /**
     * Renders the letter of [type] from the stored [payload] (subject and
     * plain-text body with the one-time link and its limited validity,
     * FR-002/FR-003/FR-010). Fails fast on a payload that misses a render
     * key — a malformed row must not silently send an empty letter.
     */
    fun render(
        type: EmailType,
        payload: Map<String, String>,
    ): RenderedEmail {
        val username = required(payload, "username")
        val link = required(payload, "link")
        return when (type) {
            EmailType.EMAIL_VERIFICATION -> emailVerification(username, link)
            EmailType.EMAIL_VERIFICATION_REPEAT -> emailVerificationRepeat(username, link)
            EmailType.PASSWORD_SETUP -> passwordSetup(username, link)
            EmailType.PASSWORD_RESET -> passwordReset(username, link)
        }
    }

    /** FR-002: the registration verification letter (confirm-registration link). */
    private fun emailVerification(
        username: String,
        link: String,
    ): RenderedEmail =
        RenderedEmail(
            subject = "Webchat — подтверждение регистрации",
            text =
                """
                Здравствуйте, $username!

                Для аккаунта Webchat с именем пользователя «$username» указан этот адрес email.
                Подтвердите его, перейдя по одноразовой ссылке:

                $link

                Ссылка действует ограниченное время и может быть использована только один раз.
                Если она не работает, запросите новую на странице регистрации.
                Если вы не регистрировались в Webchat, просто проигнорируйте это письмо.
                """.trimIndent(),
        )

    /** FR-002: the re-sent verification letter; previous links are dead. */
    private fun emailVerificationRepeat(
        username: String,
        link: String,
    ): RenderedEmail =
        RenderedEmail(
            subject = "Webchat — новая ссылка для подтверждения регистрации",
            text =
                """
                Здравствуйте, $username!

                По запросу отправляем новую ссылку для подтверждения email аккаунта «$username»:

                $link

                Предыдущие ссылки подтверждения недействительны; эта ссылка одноразовая
                и действует ограниченное время.
                Если вы не запрашивали письмо, просто проигнорируйте его.
                """.trimIndent(),
        )

    /** FR-003: the password setup letter completing the registration. */
    private fun passwordSetup(
        username: String,
        link: String,
    ): RenderedEmail =
        RenderedEmail(
            subject = "Webchat — задайте пароль",
            text =
                """
                Здравствуйте, $username!

                Email аккаунта «$username» подтверждён — осталось задать пароль для входа:

                $link

                Ссылка одноразовая и действует ограниченное время. Если она истекла,
                запросите новую повторной отправкой письма.
                Если вы не регистрировались в Webchat, просто проигнорируйте это письмо.
                """.trimIndent(),
        )

    /**
     * FR-010 (T043): the password reset letter. The old password stays valid
     * until a new one is set over the link — and setting it ends every
     * session, which the wording states up front.
     */
    private fun passwordReset(
        username: String,
        link: String,
    ): RenderedEmail =
        RenderedEmail(
            subject = "Webchat — восстановление пароля",
            text =
                """
                Здравствуйте, $username!

                Для аккаунта Webchat «$username» поступил запрос на восстановление пароля.
                Задайте новый пароль, перейдя по одноразовой ссылке:

                $link

                Ссылка одноразовая и действует ограниченное время. Прежний пароль
                останется действительным, пока новый не будет задан по этой ссылке;
                после этого все сеансы входа будут завершены.
                Если вы не запрашивали восстановление пароля, просто проигнорируйте
                это письмо — пароль не изменится.
                """.trimIndent(),
        )

    /** SPA route of the letter link (api-contract.md §1) for the open token value. */
    private fun spaLink(
        type: EmailType,
        token: String,
    ): String =
        when (type) {
            EmailType.EMAIL_VERIFICATION, EmailType.EMAIL_VERIFICATION_REPEAT ->
                "$publicBaseUrl/confirm-registration?token=$token"

            EmailType.PASSWORD_SETUP -> "$publicBaseUrl/set-password?token=$token"

            EmailType.PASSWORD_RESET -> "$publicBaseUrl/reset-password?token=$token"
        }

    private fun required(
        payload: Map<String, String>,
        key: String,
    ): String = checkNotNull(payload[key]) { "email payload is missing '$key'" }
}
