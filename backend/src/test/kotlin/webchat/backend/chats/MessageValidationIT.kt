package webchat.backend.chats

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import webchat.backend.config.ChatsProperties
import java.util.UUID

/**
 * T008a (tasks.md Phase 3, US1-4): FR-003 text validation on contract №16
 * `POST /chats/{chatId}/messages` — a single normalization rule for every
 * send: leading/trailing whitespace is trimmed, then the normalized text
 * must be non-empty and at most 4096 characters (spec Q&A: BOTH checks apply
 * to the text after trim; internal whitespace survives verbatim). Boundaries:
 * exactly 4096 after trim → `201` (even when raw outer padding pushes the
 * payload past 4096), 4097 after trim → `400 text_too_long`, spaces/tabs/
 * newlines only → `400 text_blank`, and a refused send leaves nothing in the
 * dialog (spec US1-4 «в чате ничего не появляется», Edge Cases).
 *
 * Error responses are RFC 9457 problem+json carrying the contract codes
 * `errors: {text: [text_blank|text_too_long]}` (api-contract.md №16,
 * openapi.yaml 0.4.0). The cap this IT asserts against comes from
 * `chats.message.max-length` and must equal the contract constant 4096
 * (T001 acceptance).
 *
 * NOTE (TDD, constitution VI): written BEFORE T009–T017 — until the chats
 * endpoints land, every method here fails (RED) by design.
 */
class MessageValidationIT(
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val chatsProperties: ChatsProperties,
) : MessagingTestSupport() {
    /** FR-003 upper bound: a message of exactly the cap (no padding) is stored. */
    @Test
    fun `exactly 4096 characters message is stored with 201`() {
        assertThat(chatsProperties.message.maxLength)
            .overridingErrorMessage("chats.message.max-length must stay at the contract constant 4096 (T001, FR-003)")
            .isEqualTo(CONTRACT_MAX_LENGTH)
        val (alice, bob) = messagingPair()
        val chatId = ensureChatOk(alice, bob.id)

        val boundary = "a".repeat(chatsProperties.message.maxLength)
        val response = sendMessage(alice, chatId, boundary)

        assertThat(response.statusCode)
            .overridingErrorMessage(
                "a message of exactly 4096 characters must be accepted (201), got <%s>: %s",
                response.statusCode,
                response.body,
            ).isEqualTo(HttpStatus.CREATED)
        assertThat(objectMapper.readTree(response.body)["text"].asText())
            .overridingErrorMessage("the boundary message must be stored verbatim")
            .isEqualTo(boundary)
    }

    /**
     * Spec Q&A (FR-003): the 4096 cap is measured AFTER trim, so outer
     * whitespace that pushes the raw payload past 4096 still passes when the
     * trimmed text fits — and the stored text is the trimmed one.
     */
    @Test
    fun `4096 characters after trim passes with surrounding whitespace`() {
        val (alice, bob) = messagingPair()
        val chatId = ensureChatOk(alice, bob.id)
        val core = "б".repeat(chatsProperties.message.maxLength)
        val padded = "$SURROUNDING_WHITESPACE$core$SURROUNDING_WHITESPACE"

        val response = sendMessage(alice, chatId, padded)

        assertThat(response.statusCode)
            .overridingErrorMessage(
                "leading/trailing whitespace must not count towards the 4096 cap, got <%s>: %s",
                response.statusCode,
                response.body,
            ).isEqualTo(HttpStatus.CREATED)
        assertThat(objectMapper.readTree(response.body)["text"].asText())
            .overridingErrorMessage("the stored text must be the trimmed one (FR-003 normalization)")
            .isEqualTo(core)
    }

    /**
     * FR-003 upper bound: 4097 characters after trim → `400 text_too_long`,
     * and the refused send writes nothing (US1-4).
     */
    @Test
    fun `4097 characters message is rejected with text_too_long`() {
        val (alice, bob) = messagingPair()
        val chatId = ensureChatOk(alice, bob.id)

        val response = sendMessage(alice, chatId, "c".repeat(chatsProperties.message.maxLength + 1))

        assertThat(response.statusCode)
            .overridingErrorMessage(
                "a message longer than 4096 characters after trim must be refused, got <%s>: %s",
                response.statusCode,
                response.body,
            ).isEqualTo(HttpStatus.BAD_REQUEST)
        assertProblemCode(response, TEXT_TOO_LONG)
        assertHistoryEmpty(alice, chatId)
    }

    /**
     * FR-003 emptiness check: spaces/tabs/newlines only trim to nothing →
     * `400 text_blank` (spec Edge Cases «текст только из пробелов/переводов
     * строк — отклоняется как пустой»), and no record appears in the dialog.
     */
    @Test
    fun `whitespace-only message is rejected with text_blank`() {
        val (alice, bob) = messagingPair()
        val chatId = ensureChatOk(alice, bob.id)

        val response = sendMessage(alice, chatId, BLANKISH_TEXT)

        assertThat(response.statusCode)
            .overridingErrorMessage(
                "a message consisting only of whitespace must be refused as blank, got <%s>: %s",
                response.statusCode,
                response.body,
            ).isEqualTo(HttpStatus.BAD_REQUEST)
        assertProblemCode(response, TEXT_BLANK)
        assertHistoryEmpty(alice, chatId)
    }

    /**
     * FR-003 preservation: normalization trims ONLY the outer whitespace —
     * every inner space/tab/newline sequence survives verbatim, both in the
     * №16 response and in the stored record read back through №15.
     */
    @Test
    fun `internal whitespace is preserved in the stored record`() {
        val (alice, bob) = messagingPair()
        val chatId = ensureChatOk(alice, bob.id)
        val raw = "  строка   с \n\tвнутренними\t\tпробелами\n\nи переносами   "
        val expected = "строка   с \n\tвнутренними\t\tпробелами\n\nи переносами"

        val response = sendMessage(alice, chatId, raw)

        assertThat(response.statusCode)
            .overridingErrorMessage(
                "a message with valid internal whitespace must be accepted, got <%s>: %s",
                response.statusCode,
                response.body,
            ).isEqualTo(HttpStatus.CREATED)
        assertThat(objectMapper.readTree(response.body)["text"].asText())
            .overridingErrorMessage("№16 must return the normalized text with internal whitespace intact")
            .isEqualTo(expected)

        val history = listMessages(alice, chatId)
        assertThat(history.statusCode)
            .overridingErrorMessage("history №15 must answer 200, got <%s>: %s", history.statusCode, history.body)
            .isEqualTo(HttpStatus.OK)
        val messages = objectMapper.readTree(history.body)["messages"]
        assertThat(messages.size())
            .overridingErrorMessage("the dialog must hold exactly the one valid message, got: %s", history.body)
            .isEqualTo(1)
        assertThat(messages[0]["text"].asText())
            .overridingErrorMessage("the stored record must keep internal whitespace verbatim (FR-003)")
            .isEqualTo(expected)
    }

    /** US1-4: a refused send leaves the dialog history untouched and empty. */
    private fun assertHistoryEmpty(
        user: MessagingUser,
        chatId: UUID,
    ) {
        val history = listMessages(user, chatId)
        assertThat(history.statusCode)
            .overridingErrorMessage("history №15 must answer 200, got <%s>: %s", history.statusCode, history.body)
            .isEqualTo(HttpStatus.OK)
        assertThat(objectMapper.readTree(history.body)["messages"].size())
            .overridingErrorMessage(
                "a refused send must leave nothing in the dialog (US1-4 «в чате ничего не появляется»), got: %s",
                history.body,
            ).isZero
    }

    private fun assertProblemCode(
        response: ResponseEntity<String>,
        code: String,
    ) {
        assertThat(response.headers.contentType?.toString())
            .overridingErrorMessage("validation refusals must be RFC 9457 application/problem+json")
            .contains(PROBLEM_JSON_MEDIA_TYPE)
        val problem = objectMapper.readTree(response.body)
        val codes = problem["errors"]?.get(TEXT_FIELD)?.map { it.asText() }.orEmpty()
        assertThat(codes)
            .overridingErrorMessage(
                "the problem must carry the contract code errors.%s=[%s], got <%s> in %s",
                TEXT_FIELD,
                code,
                codes,
                response.body,
            ).containsExactly(code)
    }

    private companion object {
        const val PROBLEM_JSON_MEDIA_TYPE = "application/problem+json"
        const val TEXT_FIELD = "text"
        const val TEXT_TOO_LONG = "text_too_long"
        const val TEXT_BLANK = "text_blank"
        const val CONTRACT_MAX_LENGTH = 4096
        const val SURROUNDING_WHITESPACE = " \n\t "
        const val BLANKISH_TEXT = "   \t\n \n\t "
    }
}
