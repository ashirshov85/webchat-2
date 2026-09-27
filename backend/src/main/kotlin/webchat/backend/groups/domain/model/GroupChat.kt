package webchat.backend.groups.domain.model

import java.time.Instant
import java.util.UUID

/**
 * The single violated FR-001 rule for a candidate group title; the API
 * layer renders it as the contract error code `invalid_title`
 * (api-contract.md №27/№29, T017 GroupsExceptionHandler).
 */
enum class GroupTitleViolation {
    BLANK,
    TOO_LONG,
}

/**
 * FR-001 refusal: carries only the violated rule — the submitted title is
 * never echoed (constitution V, SC-008 warn-logs without PII).
 */
class InvalidGroupTitleException(
    val violation: GroupTitleViolation,
) : IllegalArgumentException("group title violates FR-001: $violation")

/**
 * The single violated FR-001 rule for a candidate group description; the
 * API layer renders it as the contract error code `invalid_description`
 * (api-contract.md №27/№29, T017 GroupsExceptionHandler). The description
 * is optional, so blank is NOT a violation — an empty value is stored as
 * the absence of a description.
 */
enum class GroupDescriptionViolation {
    TOO_LONG,
}

/**
 * FR-001 refusal: carries only the violated rule — the submitted
 * description is never echoed (constitution V).
 */
class InvalidGroupDescriptionException(
    val violation: GroupDescriptionViolation,
) : IllegalArgumentException("group description violates FR-001: $violation")

/**
 * Normalized group title (data-model 006 §Сущность 1, FR-001): leading/
 * trailing whitespace is trimmed, the result is 1..[MAX_LENGTH] (64)
 * characters — BOTH bounds apply to the value AFTER trim, mirroring the
 * V14 CHECK `ck_chats_title` (`length(btrim(title)) BETWEEN 1 AND 64`),
 * so a stored row always re-validates; internal whitespace survives
 * verbatim.
 */
data class GroupTitle private constructor(
    val value: String,
) {
    init {
        require(value.isNotEmpty()) { "group title must not be blank" }
        require(value.length <= MAX_LENGTH) { "group title must not exceed $MAX_LENGTH characters" }
    }

    companion object {
        /** Contract constant (api-contract.md header, FR-001; asserted by T005a). */
        const val MAX_LENGTH: Int = 64

        /**
         * Applies the FR-001 rule to a raw create/patch: trims outer
         * whitespace, then rejects blank/oversized results with the
         * contract violation. [maxLength] defaults to the contract cap and
         * is parameterized by `groups.title-max-length` (T004) so the
         * validation source stays single.
         */
        fun normalize(
            raw: String,
            maxLength: Int = MAX_LENGTH,
        ): GroupTitle {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) throw InvalidGroupTitleException(GroupTitleViolation.BLANK)
            if (trimmed.length > maxLength) throw InvalidGroupTitleException(GroupTitleViolation.TOO_LONG)
            return GroupTitle(trimmed)
        }
    }
}

/**
 * Optional group description (data-model 006 §Сущность 1, FR-001): at
 * most [MAX_LENGTH] (256) characters, NO trim is mandated — mirrors the
 * V14 CHECK `ck_chats_description` (`length(description) <= 256`). The
 * empty value is valid; the service treats it as «no description».
 */
data class GroupDescription private constructor(
    val value: String,
) {
    init {
        require(value.length <= MAX_LENGTH) { "group description must not exceed $MAX_LENGTH characters" }
    }

    companion object {
        /** Contract constant (api-contract.md header, FR-001; asserted by T005a). */
        const val MAX_LENGTH: Int = 256

        /**
         * Applies the FR-001 rule to a raw create/patch: rejects only an
         * oversized value with the contract violation. [maxLength]
         * defaults to the contract cap and is parameterized by
         * `groups.description-max-length` (T004) so the validation source
         * stays single.
         */
        fun normalize(
            raw: String,
            maxLength: Int = MAX_LENGTH,
        ): GroupDescription {
            if (raw.length > maxLength) throw InvalidGroupDescriptionException(GroupDescriptionViolation.TOO_LONG)
            return GroupDescription(raw)
        }
    }
}

/**
 * Role of a group member (data-model 006 §Сущность 2): `owner` — the
 * single group owner (exactly one active OWNER per group is enforced by
 * the partial unique index `ux_chat_participants_owner`, FR-003);
 * `admin` — elevated by the owner (FR-004 hierarchy); `member` — plain
 * participant. Meaningful only on an active membership row of a group;
 * direct-dialog rows carry `NULL` role.
 */
enum class MemberRole {
    OWNER,
    ADMIN,
    MEMBER,
}

/**
 * Membership lifecycle of a participant row (data-model 006 §Сущность 2):
 * membership in a group ⟺ an [ACTIVE] row (FR-008 — the gate treats
 * [REMOVED] as a non-member); [REMOVED] preserves the 004/005 watermarks
 * for re-adding (FR-002) and reactivation resets the role to `member`.
 * Direct-dialog rows are always [ACTIVE] and never leave it.
 */
enum class MembershipState {
    ACTIVE,
    REMOVED,
}

/**
 * Group projection of `chats` (data-model 006 §Сущность 1): the row with
 * `kind='group'` — shared metadata ([title], optional [description]), the
 * common history head cache [lastSeq] (unchanged 004 semantics: sorted
 * lists, ack/read validation, watermark initialization on add) and the
 * server generation time. The dialog user pair is NULL by the V14
 * shape-CHECK, so it is not modelled here; the roster lives in
 * `chat_participants` rows with [MemberRole]/[MembershipState].
 */
data class GroupChat(
    val id: UUID,
    val title: GroupTitle,
    val description: GroupDescription?,
    val createdAt: Instant,
    val lastSeq: Long = 0,
)
