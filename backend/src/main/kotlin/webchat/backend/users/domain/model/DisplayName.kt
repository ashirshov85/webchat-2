package webchat.backend.users.domain.model

/**
 * FR-001 refusal: the API layer (T012) renders it as the single contract
 * error code `400 invalid_display_name` of №39 (api-contract.md §1) —
 * blank-after-trim and oversized share ONE code, so there is no violation
 * split (unlike `text_blank`/`text_too_long` of №16). The submitted value
 * is never echoed (constitution V).
 */
class InvalidDisplayNameException :
    IllegalArgumentException("display name violates FR-001: must be 1..${DisplayName.MAX_LENGTH} chars after trim")

/**
 * Normalized profile display name (data-model 008a §1.1, FR-001):
 * `users.display_name`, NULL = «not set» → the display chain falls back
 * to `username`. The value is stored AFTER the server-side trim:
 * non-blank and at most [MAX_LENGTH] (64) characters, BOTH checks on the
 * trimmed form (US1 AC5); internal whitespace survives verbatim; Unicode
 * is unrestricted (cyrillic/emoji, US1 edge). Mirrors the
 * `ck_users_display_name` CHECK of V16, so a stored row always
 * re-validates on read (the [MessageText][webchat.backend.chats.domain.model.MessageText]
 * discipline).
 */
data class DisplayName private constructor(
    val value: String,
) {
    init {
        require(value.isNotEmpty()) { "display name must not be blank" }
        require(value.length <= MAX_LENGTH) { "display name must not exceed $MAX_LENGTH characters" }
    }

    companion object {
        /** Contract constant (api-contract.md §1 №39, FR-001): the 64-character bound after trim. */
        const val MAX_LENGTH: Int = 64

        /**
         * Applies the single FR-001 rule to a raw №39 write: trims outer
         * whitespace, then rejects the blank/oversized result with
         * [InvalidDisplayNameException] — rendered `400
         * invalid_display_name` by the API layer (T012). The reset leg is
         * NOT routed here: `null`/an omitted field means «not set» and is
         * carried by the caller as a plain `null` (api-contract.md §1
         * №39: «""/пробельная строка = 400, сброс — только явный null»).
         */
        fun normalize(raw: String): DisplayName {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) throw InvalidDisplayNameException()
            if (trimmed.length > MAX_LENGTH) throw InvalidDisplayNameException()
            return DisplayName(trimmed)
        }
    }
}
