package webchat.backend.chats.domain.model

/**
 * The single violated FR-003 rule for a candidate message text; the API
 * layer renders it as the contract error code `text_blank` / `text_too_long`
 * (api-contract.md №16, T017).
 */
enum class MessageTextViolation {
    BLANK,
    TOO_LONG,
}

/**
 * FR-003 refusal: carries only the violated rule — the submitted text is
 * never echoed (constitution V, SC-008 warn-logs without PII).
 */
class InvalidMessageTextException(
    val violation: MessageTextViolation,
) : IllegalArgumentException("message text violates FR-003: $violation")

/**
 * Normalized message text (data-model 004 §3, FR-003): leading/trailing
 * whitespace is trimmed, the result is non-empty and at most
 * [MAX_LENGTH] (4096) characters — BOTH checks apply to the text AFTER
 * trim (spec Q&A); internal whitespace survives verbatim. Mirrors the
 * `ck_messages_text_length` CHECK of V10, so a stored row always
 * re-validates.
 */
data class MessageText private constructor(
    val value: String,
) {
    init {
        require(value.isNotEmpty()) { "message text must not be blank" }
        require(value.length <= MAX_LENGTH) { "message text must not exceed $MAX_LENGTH characters" }
    }

    companion object {
        /** Contract constant (api-contract.md header, FR-003; asserted by T008a/T001). */
        const val MAX_LENGTH: Int = 4096

        /**
         * Applies the single FR-003 rule to a raw send: trims outer
         * whitespace, then rejects blank/oversized results with the
         * contract violation. [maxLength] defaults to the contract cap and
         * is parameterized by `chats.message.max-length` (T001) so the
         * validation source stays single.
         */
        fun normalize(
            raw: String,
            maxLength: Int = MAX_LENGTH,
        ): MessageText {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) throw InvalidMessageTextException(MessageTextViolation.BLANK)
            if (trimmed.length > maxLength) throw InvalidMessageTextException(MessageTextViolation.TOO_LONG)
            return MessageText(trimmed)
        }
    }
}
