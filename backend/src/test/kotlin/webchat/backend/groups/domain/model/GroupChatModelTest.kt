package webchat.backend.groups.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Unit-level mirror of the FR-001 metadata rules mandated for T005
 * (api-contract.md header + №27/№29; data-model.md §Сущность 1): the group
 * title is trimmed and must be 1–64 characters AFTER trim — both checks
 * apply to the trimmed value, mirroring the V14 CHECK `ck_chats_title`
 * (`length(btrim(title)) BETWEEN 1 AND 64`); the optional description is
 * at most 256 characters (V14 CHECK `ck_chats_description`, no trim
 * mandated). The participant projection carries the role enum
 * (`owner`/`admin`/`member`, data-model.md §Сущность 2 — exactly one
 * owner per group is enforced by `ux_chat_participants_owner`) and the
 * membership state enum (`active`/`removed` — membership ⟺ active row).
 *
 * The HTTP rendering of refusals as `invalid_title`/`invalid_description`
 * (T017, GroupsExceptionHandler), the schema-0.6.0 DTO mapping (T016) and
 * persistence behaviour (T012a, GroupRepositoryIT) are deliberately out
 * of scope.
 *
 * Security (constitution V): a refusal must never echo the submitted
 * value — asserted below on the exception messages.
 */
class GroupChatModelTest {
    // --- GroupTitle ---

    @Test
    fun `title normalizes by trimming leading and trailing whitespace`() {
        assertThat(GroupTitle.normalize("  проект \t").value).isEqualTo("проект")
        assertThat(GroupTitle.normalize("\n\t Смешанный отступ \r\n").value).isEqualTo("Смешанный отступ")
    }

    @Test
    fun `title preserves internal whitespace verbatim`() {
        val raw = "  строка   с \n\tвнутренними\t\tпробелами\n\nи переносами   "

        val title = GroupTitle.normalize(raw)

        assertThat(title.value)
            .isEqualTo("строка   с \n\tвнутренними\t\tпробелами\n\nи переносами")
    }

    @Test
    fun `title rejects empty input as blank`() {
        val exception = assertThrows<InvalidGroupTitleException> { GroupTitle.normalize("") }

        assertThat(exception.violation).isEqualTo(GroupTitleViolation.BLANK)
    }

    @Test
    fun `title rejects whitespace-only input as blank`() {
        for (raw in listOf("   \t\n \n\t ", " ", "\t", "\n \r\n")) {
            val exception = assertThrows<InvalidGroupTitleException> { GroupTitle.normalize(raw) }

            assertThat(exception.violation).isEqualTo(GroupTitleViolation.BLANK)
        }
    }

    @Test
    fun `title accepts a single character after trim`() {
        assertThat(GroupTitle.normalize(" я ").value).isEqualTo("я")
    }

    @Test
    fun `title accepts exactly the contract cap of 64 characters`() {
        val boundary = "a".repeat(GroupTitle.MAX_LENGTH)

        assertThat(GroupTitle.normalize(boundary).value).isEqualTo(boundary)
    }

    @Test
    fun `title cap is measured after trim so outer padding does not count`() {
        val core = "б".repeat(GroupTitle.MAX_LENGTH)

        val title = GroupTitle.normalize(" \n\t$core \n\t")

        assertThat(title.value).isEqualTo(core)
    }

    @Test
    fun `title rejects 65 characters`() {
        val tooLong = "c".repeat(GroupTitle.MAX_LENGTH + 1)

        val exception = assertThrows<InvalidGroupTitleException> { GroupTitle.normalize(tooLong) }

        assertThat(exception.violation).isEqualTo(GroupTitleViolation.TOO_LONG)
    }

    @Test
    fun `title default cap equals the public contract constant 64`() {
        assertThat(GroupTitle.MAX_LENGTH).isEqualTo(64)
    }

    @Test
    fun `title honours a configured cap from groups_title-max-length`() {
        val configured = 10

        assertThat(GroupTitle.normalize("проект", configured).value).isEqualTo("проект")
        val exception =
            assertThrows<InvalidGroupTitleException> {
                GroupTitle.normalize("одиннадцать", configured)
            }

        assertThat(exception.violation).isEqualTo(GroupTitleViolation.TOO_LONG)
    }

    @Test
    fun `title refusal never echoes the submitted value`() {
        val raw = "секретное название " + "x".repeat(GroupTitle.MAX_LENGTH)

        val exception = assertThrows<InvalidGroupTitleException> { GroupTitle.normalize(raw) }

        assertThat(exception.violation).isEqualTo(GroupTitleViolation.TOO_LONG)
        assertThat(exception.message).doesNotContain("секретное")
    }

    // --- GroupDescription ---

    @Test
    fun `description accepts exactly the contract cap of 256 characters`() {
        val boundary = "a".repeat(GroupDescription.MAX_LENGTH)

        assertThat(GroupDescription.normalize(boundary).value).isEqualTo(boundary)
    }

    @Test
    fun `description rejects 257 characters`() {
        val tooLong = "c".repeat(GroupDescription.MAX_LENGTH + 1)

        val exception = assertThrows<InvalidGroupDescriptionException> { GroupDescription.normalize(tooLong) }

        assertThat(exception.violation).isEqualTo(GroupDescriptionViolation.TOO_LONG)
    }

    @Test
    fun `description allows empty value because it is optional`() {
        assertThat(GroupDescription.normalize("").value).isEmpty()
    }

    @Test
    fun `description default cap equals the public contract constant 256`() {
        assertThat(GroupDescription.MAX_LENGTH).isEqualTo(256)
    }

    @Test
    fun `description honours a configured cap from groups_description-max-length`() {
        val configured = 10

        assertThat(GroupDescription.normalize("заметка", configured).value).isEqualTo("заметка")
        val exception =
            assertThrows<InvalidGroupDescriptionException> {
                GroupDescription.normalize("одиннадцать", configured)
            }

        assertThat(exception.violation).isEqualTo(GroupDescriptionViolation.TOO_LONG)
    }

    @Test
    fun `description refusal never echoes the submitted value`() {
        val raw = "секретное описание " + "x".repeat(GroupDescription.MAX_LENGTH)

        val exception = assertThrows<InvalidGroupDescriptionException> { GroupDescription.normalize(raw) }

        assertThat(exception.violation).isEqualTo(GroupDescriptionViolation.TOO_LONG)
        assertThat(exception.message).doesNotContain("секретное")
    }

    // --- MemberRole / MembershipState ---

    @Test
    fun `member roles are exactly owner admin member`() {
        assertThat(enumValues<MemberRole>().map { it.name })
            .containsExactly("OWNER", "ADMIN", "MEMBER")
    }

    @Test
    fun `membership states are exactly active removed`() {
        assertThat(enumValues<MembershipState>().map { it.name })
            .containsExactly("ACTIVE", "REMOVED")
    }
}
