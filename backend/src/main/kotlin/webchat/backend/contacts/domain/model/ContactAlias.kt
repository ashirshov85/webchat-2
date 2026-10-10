package webchat.backend.contacts.domain.model

/**
 * FR-003 refusal: the API layer (T014) renders it as the single contract
 * error code `400 invalid_alias` of №40 (api-contract.md §1) —
 * blank-after-trim and oversized share ONE code, exactly like
 * [invalid_display_name][webchat.backend.users.domain.model.InvalidDisplayNameException]
 * of №39. The submitted value is never echoed (constitution V).
 */
class InvalidAliasException :
    IllegalArgumentException("contact alias violates FR-003: must be 1..${ContactAlias.MAX_LENGTH} chars after trim")

/**
 * Normalized personal contact alias (data-model 008a §1.2, FR-003):
 * `user_contacts.alias` — the OWNER's private name for the contact,
 * NULL = «not set» → the display chain falls back to
 * [displayName][webchat.backend.users.domain.model.DisplayName] and
 * then `username`. The value is stored AFTER the server-side trim:
 * non-blank and at most [MAX_LENGTH] (64) characters, BOTH checks on
 * the trimmed form (US1 AC5); internal whitespace survives verbatim;
 * Unicode is unrestricted (cyrillic/emoji, US1 edge). Mirrors the
 * `ck_user_contacts_alias` CHECK of V16, so a stored row always
 * re-validates on read (the [MessageText][webchat.backend.chats.domain.model.MessageText]
 * discipline). The alias is readable by the owner ONLY (FR-003) —
 * every projection is the caller's own row (`owner_id = me`).
 */
data class ContactAlias private constructor(
    val value: String,
) {
    init {
        require(value.isNotEmpty()) { "contact alias must not be blank" }
        require(value.length <= MAX_LENGTH) { "contact alias must not exceed $MAX_LENGTH characters" }
    }

    companion object {
        /** Contract constant (api-contract.md §1 №40, FR-003): the 64-character bound after trim. */
        const val MAX_LENGTH: Int = 64

        /**
         * Applies the single FR-003 rule to a raw №40 write: trims outer
         * whitespace, then rejects the blank/oversized result with
         * [InvalidAliasException] — rendered `400 invalid_alias` by the
         * API layer (T014). The reset leg is NOT routed here: `null`/an
         * omitted field means «not set» and is carried by the caller as
         * a plain `null` (api-contract.md §1 №40: «""/пробельная строка
         * = 400, сброс — только явный null»).
         */
        fun normalize(raw: String): ContactAlias {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) throw InvalidAliasException()
            if (trimmed.length > MAX_LENGTH) throw InvalidAliasException()
            return ContactAlias(trimmed)
        }
    }
}
