package webchat.backend.chats.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Unit-level mirror of the FR-003 normalization mandated for T009: a single
 * rule for every send — leading/trailing whitespace is trimmed, then the
 * normalized text must be non-empty and at most 4096 characters (spec Q&A:
 * BOTH checks apply to the text AFTER trim; internal whitespace survives
 * verbatim). The end-to-end contract checks (400 `text_blank` /
 * `text_too_long` on №16) live in MessageValidationIT (T008a).
 *
 * Security (constitution V): the refusal must never echo the submitted
 * text — asserted below on the exception message.
 */
class MessageTextTest {
    @Test
    fun `normalizes by trimming leading and trailing whitespace`() {
        assertThat(MessageText.normalize("  привет  ").value).isEqualTo("привет")
        assertThat(MessageText.normalize("\n\t смешанный отступ \r\n").value).isEqualTo("смешанный отступ")
    }

    @Test
    fun `preserves internal whitespace verbatim`() {
        val raw = "  строка   с \n\tвнутренними\t\tпробелами\n\nи переносами   "

        val text = MessageText.normalize(raw)

        assertThat(text.value)
            .isEqualTo("строка   с \n\tвнутренними\t\tпробелами\n\nи переносами")
    }

    @Test
    fun `rejects empty text`() {
        val exception = assertThrows<InvalidMessageTextException> { MessageText.normalize("") }

        assertThat(exception.violation).isEqualTo(MessageTextViolation.BLANK)
    }

    @Test
    fun `rejects whitespace-only text as blank`() {
        for (raw in listOf("   \t\n \n\t ", " ", "\t", "\n \r\n")) {
            val exception = assertThrows<InvalidMessageTextException> { MessageText.normalize(raw) }

            assertThat(exception.violation).isEqualTo(MessageTextViolation.BLANK)
        }
    }

    @Test
    fun `accepts exactly the contract cap of 4096 characters`() {
        val boundary = "a".repeat(MessageText.MAX_LENGTH)

        assertThat(MessageText.normalize(boundary).value).isEqualTo(boundary)
    }

    @Test
    fun `cap is measured after trim so outer padding does not count`() {
        val core = "б".repeat(MessageText.MAX_LENGTH)

        val text = MessageText.normalize(" \n\t$core \n\t")

        assertThat(text.value).isEqualTo(core)
    }

    @Test
    fun `rejects text longer than the cap after trim`() {
        val tooLong = "c".repeat(MessageText.MAX_LENGTH + 1)

        val exception = assertThrows<InvalidMessageTextException> { MessageText.normalize(tooLong) }

        assertThat(exception.violation).isEqualTo(MessageTextViolation.TOO_LONG)
    }

    @Test
    fun `default cap equals the public contract constant 4096`() {
        assertThat(MessageText.MAX_LENGTH).isEqualTo(4096)
    }

    @Test
    fun `honours a configured cap from chats_message_max-length`() {
        val configured = 10

        assertThat(MessageText.normalize("слово", configured).value).isEqualTo("слово")
        val exception =
            assertThrows<InvalidMessageTextException> {
                MessageText.normalize("одиннадцать", configured)
            }

        assertThat(exception.violation).isEqualTo(MessageTextViolation.TOO_LONG)
    }

    @Test
    fun `refusal never echoes the submitted text`() {
        val raw = "секретное содержимое " + "x".repeat(MessageText.MAX_LENGTH)

        val exception = assertThrows<InvalidMessageTextException> { MessageText.normalize(raw) }

        assertThat(exception.violation).isEqualTo(MessageTextViolation.TOO_LONG)
        assertThat(exception.message).doesNotContain("секретное")
    }
}
