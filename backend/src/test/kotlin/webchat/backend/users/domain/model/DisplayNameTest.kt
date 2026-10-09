package webchat.backend.users.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Unit-level mirror of the FR-001 normalization mandated for T011: a
 * single rule for every №39 write — leading/trailing whitespace is
 * trimmed, then the normalized name must be non-empty and at most 64
 * characters (US1 AC5: BOTH checks apply to the name AFTER trim;
 * internal whitespace survives verbatim; Unicode is unrestricted — US1
 * edge). The end-to-end contract check (`400 invalid_display_name` on
 * №39) lives in ProfileDisplayNameIT (T009).
 *
 * Security (constitution V): the refusal must never echo the submitted
 * name — asserted below on the exception message.
 */
class DisplayNameTest {
    @Test
    fun `normalizes by trimming leading and trailing whitespace`() {
        assertThat(DisplayName.normalize("  Мария  ").value).isEqualTo("Мария")
        assertThat(DisplayName.normalize("\n\t Маша \r\n").value).isEqualTo("Маша")
    }

    @Test
    fun `preserves internal whitespace verbatim`() {
        val raw = "  Мария   Викторовна\tКовалёва  "

        val name = DisplayName.normalize(raw)

        assertThat(name.value).isEqualTo("Мария   Викторовна\tКовалёва")
    }

    @Test
    fun `rejects an empty name`() {
        assertThrows<InvalidDisplayNameException> { DisplayName.normalize("") }
    }

    @Test
    fun `rejects a whitespace-only name`() {
        for (raw in listOf("   \t\n \n\t ", " ", "\t", "\n \r\n")) {
            assertThrows<InvalidDisplayNameException> { DisplayName.normalize(raw) }
        }
    }

    @Test
    fun `accepts exactly the contract cap of 64 characters`() {
        val boundary = "м".repeat(DisplayName.MAX_LENGTH)

        assertThat(DisplayName.normalize(boundary).value).isEqualTo(boundary)
    }

    @Test
    fun `cap is measured after trim so outer padding does not count`() {
        val core = "б".repeat(DisplayName.MAX_LENGTH)

        val name = DisplayName.normalize(" \n\t$core \n\t")

        assertThat(name.value).isEqualTo(core)
    }

    @Test
    fun `rejects a name longer than the cap after trim`() {
        val tooLong = "c".repeat(DisplayName.MAX_LENGTH + 1)

        assertThrows<InvalidDisplayNameException> { DisplayName.normalize(tooLong) }
    }

    @Test
    fun `unicode is unrestricted — cyrillic and emoji names are valid`() {
        assertThat(DisplayName.normalize("🙂Мария🙂").value).isEqualTo("🙂Мария🙂")
    }

    @Test
    fun `default cap equals the public contract constant 64`() {
        assertThat(DisplayName.MAX_LENGTH).isEqualTo(64)
    }

    @Test
    fun `refusal never echoes the submitted name`() {
        val raw = "секретное имя " + "x".repeat(DisplayName.MAX_LENGTH)

        val exception = assertThrows<InvalidDisplayNameException> { DisplayName.normalize(raw) }

        assertThat(exception.message).doesNotContain("секретное")
    }
}
