package webchat.backend.groups.domain.service

/**
 * The SINGLE typed 404 carrier of the FR-008 membership gate
 * (`group_not_found`, api-contract.md §1): a stranger, a removed former
 * member and an unknown chat id all render IDENTICALLY — the group's
 * existence is never disclosed. The message carries no chat or user
 * identifiers (constitution V).
 *
 * Thrown by [GroupMembershipGate] (T015), rendered by T017; pinned
 * red-first by GroupMembershipGateTest (T015a).
 */
class GroupNotFoundException : RuntimeException("The requested group was not found") {
    val code: String = CODE_GROUP_NOT_FOUND
}

private const val CODE_GROUP_NOT_FOUND = "group_not_found"
