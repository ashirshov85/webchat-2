package webchat.backend.conferences.repository

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import webchat.backend.AbstractIntegrationTest
import webchat.backend.chats.domain.model.MessageText
import webchat.backend.chats.domain.port.NewSystemMessage
import webchat.backend.chats.repository.JdbcChatRepository
import webchat.backend.chats.repository.JdbcMessageRepository
import webchat.backend.conferences.api.dto.ConferenceMode
import webchat.backend.conferences.domain.port.ConferenceModeReader
import java.util.UUID

/**
 * T019 regression IT: the [JdbcConferenceModeReader] adapter over the
 * real Testcontainers PG — the `ConferenceView.mode` source of №45 and
 * the №44 `200 Existing` leg (the conferences row stores NO mode,
 * data-model 009 §Сущность 1; the widget payload of the chat history
 * is the single source, §Сущность 4):
 *
 *  * a chat whose history carries the invite widgets of SEVERAL
 *    conferences (every previous one ended — the long-lived-chat
 *    reality) resolves EACH conferenceId to ITS OWN widget mode;
 *  * an unknown conferenceId folds to `null` — the caller's
 *    broken-invariant path (the widget rides the create transaction,
 *    T017), never a guess;
 *  * an unknown `payload.mode` label folds to `null` the same way
 *    (constitution III — no defaults on the read side).
 */
class JdbcConferenceModeReaderIT : AbstractIntegrationTest() {
    @Autowired
    private lateinit var modeReader: ConferenceModeReader

    @Autowired
    private lateinit var chatRepository: JdbcChatRepository

    @Autowired
    private lateinit var messageRepository: JdbcMessageRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    private val objectMapper = ObjectMapper()

    @Test
    fun `each conference of a chat resolves its own widget mode`() {
        val chat = newChat()
        val video = UUID.randomUUID()
        val audio = UUID.randomUUID()
        appendInviteWidget(chat.id, video, "video")
        appendInviteWidget(chat.id, audio, "audio")

        assertThat(modeReader.modeOf(video, chat.id))
            .overridingErrorMessage("the widget of THIS conference answers, not the latest of the chat")
            .isEqualTo(ConferenceMode.VIDEO)
        assertThat(modeReader.modeOf(audio, chat.id)).isEqualTo(ConferenceMode.AUDIO)
    }

    @Test
    fun `an unknown conference id resolves null`() {
        val chat = newChat()
        appendInviteWidget(chat.id, UUID.randomUUID(), "video")

        assertThat(modeReader.modeOf(UUID.randomUUID(), chat.id)).isNull()
    }

    @Test
    fun `an unknown mode label folds to null`() {
        val chat = newChat()
        val conference = UUID.randomUUID()
        appendInviteWidget(chat.id, conference, "voice")

        assertThat(modeReader.modeOf(conference, chat.id)).isNull()
    }

    /** A user row + the direct chat the widgets land in (the plain JdbcTemplate fixture of the T010 ITs). */
    private fun newChat() = chatRepository.ensure(newUser(), newUser()).chat

    private fun newUser(): UUID {
        val id = UUID.randomUUID()
        val login = "t019m-${id.toString().substring(0, 8)}"
        jdbcTemplate.update(INSERT_USER_SQL, id, login, "$login@example.com")
        return id
    }

    /** The `conference_invite` widget row of data-model 009 §Сущность 4, exactly as T017 posts it. */
    private fun appendInviteWidget(
        chatId: UUID,
        conferenceId: UUID,
        mode: String,
    ) {
        val payload =
            objectMapper
                .createObjectNode()
                .put("type", "conference_invite")
                .put("conferenceId", conferenceId.toString())
                .put("mode", mode)
        messageRepository.insertSystem(
            NewSystemMessage(
                id = UUID.randomUUID(),
                chatId = chatId,
                text = MessageText.normalize("Видеоконференция"),
                payload = payload,
            ),
        )
    }

    private companion object {
        val INSERT_USER_SQL =
            """
            INSERT INTO users (id, username, email, status)
            VALUES (?, ?, ?, 'pending_email_confirmation')
            """.trimIndent()
    }
}
