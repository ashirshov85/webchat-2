package webchat.backend.chats

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import java.util.UUID

/**
 * T008 (tasks.md Phase 3, US1-4): membership authorization of FR-002 —
 * every dialog resource answers `403 not_participant` to a registered user
 * outside the pair (Carol), and `404 chat_not_found` for an unknown chat id,
 * uniformly on contract №13 `GET /chats/{id}`, №14 `DELETE /chats/{id}`,
 * №15 `GET /chats/{id}/messages`, №16 `POST /chats/{id}/messages` and
 * №17 `POST /chats/{id}/read` — RFC 9457 problem+json carrying the contract
 * `errors: {chat: [...]}` code (api-contract.md §1, openapi.yaml 0.4.0).
 *
 * The stranger's POSTs carry fully valid `SendMessageRequest`/`ReadRequest`
 * bodies on purpose, so membership — not payload validation — is the only
 * reason for the rejection; after the refused sweep the dialog stays intact
 * for the participants (nothing deleted, nothing written).
 *
 * NOTE (TDD, constitution VI): written BEFORE T009–T017 — until the chats
 * endpoints land, every method here fails (RED) by design.
 */
class ChatAccessIT(
    @Autowired private val objectMapper: ObjectMapper,
) : MessagingTestSupport() {
    /** FR-002: a registered non-participant is refused on EVERY chat resource. */
    @Test
    fun `stranger gets 403 not_participant on every chat resource`() {
        val (alice, bob) = messagingPair()
        val carol = strangerUser()
        val chatId = ensureChatOk(alice, bob.id)

        assertMembershipRefusal(getChat(carol, chatId))
        assertMembershipRefusal(deleteChat(carol, chatId))
        assertMembershipRefusal(listMessages(carol, chatId))
        assertMembershipRefusal(sendMessage(carol, chatId, STRANGER_TEXT))
        assertMembershipRefusal(markRead(carol, chatId, upToSeq = VALID_UP_TO_SEQ))

        // The refused sweep changed nothing for the participants (FR-002).
        assertThat(getChat(alice, chatId).statusCode)
            .overridingErrorMessage("after a stranger's refused DELETE the dialog must remain for the participant")
            .isEqualTo(HttpStatus.OK)
    }

    /**
     * FR-002/contract: an unknown chat id answers `404 chat_not_found` —
     * uniformly on every operation of the resource family, for any
     * authenticated caller (api-contract.md: not_participant is defined only
     * for an existing chat; the 404 precedes any participant distinction).
     */
    @Test
    fun `unknown chat answers 404 chat_not_found on every chat resource`() {
        val alice = messagingUser("ghost")
        val carol = strangerUser()
        val unknownChatId = UUID.randomUUID()

        assertChatMissing(getChat(alice, unknownChatId))
        assertChatMissing(deleteChat(alice, unknownChatId))
        assertChatMissing(listMessages(alice, unknownChatId))
        assertChatMissing(sendMessage(alice, unknownChatId, GHOST_TEXT))
        assertChatMissing(markRead(alice, unknownChatId, upToSeq = VALID_UP_TO_SEQ))

        // A stranger learns nothing beyond the 404 either.
        assertChatMissing(getChat(carol, unknownChatId))
    }

    private fun assertMembershipRefusal(response: ResponseEntity<String>) {
        assertThat(response.statusCode)
            .overridingErrorMessage(
                "a non-participant must get 403 on chat resources, got <%s>: %s",
                response.statusCode,
                response.body,
            ).isEqualTo(HttpStatus.FORBIDDEN)
        assertProblemCode(response, NOT_PARTICIPANT)
    }

    private fun assertChatMissing(response: ResponseEntity<String>) {
        assertThat(response.statusCode)
            .overridingErrorMessage(
                "an unknown chat must answer 404, got <%s>: %s",
                response.statusCode,
                response.body,
            ).isEqualTo(HttpStatus.NOT_FOUND)
        assertProblemCode(response, CHAT_NOT_FOUND)
    }

    private fun assertProblemCode(
        response: ResponseEntity<String>,
        code: String,
    ) {
        assertThat(response.headers.contentType?.toString())
            .overridingErrorMessage("membership refusals must be RFC 9457 application/problem+json")
            .contains(PROBLEM_JSON_MEDIA_TYPE)
        val problem = objectMapper.readTree(response.body)
        val codes = problem["errors"]?.get(CHAT_FIELD)?.map { it.asText() }.orEmpty()
        assertThat(codes)
            .overridingErrorMessage(
                "the problem must carry the contract code errors.%s=[%s], got <%s> in %s",
                CHAT_FIELD,
                code,
                codes,
                response.body,
            ).containsExactly(code)
    }

    private companion object {
        const val PROBLEM_JSON_MEDIA_TYPE = "application/problem+json"
        const val CHAT_FIELD = "chat"
        const val NOT_PARTICIPANT = "not_participant"
        const val CHAT_NOT_FOUND = "chat_not_found"
        const val VALID_UP_TO_SEQ = 1L

        // Valid per FR-003 on purpose: membership must be the only rejection reason.
        const val STRANGER_TEXT = "Carol is not a participant of this dialog"
        const val GHOST_TEXT = "addressed to a chat that does not exist"
    }
}
