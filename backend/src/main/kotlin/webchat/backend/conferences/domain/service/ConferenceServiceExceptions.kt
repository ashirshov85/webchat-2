package webchat.backend.conferences.domain.service

/**
 * 404 (api-contract.md 009 №44): the chatId of the create call
 * resolves to nothing — the chats `404 chat_not_found` code verbatim,
 * answered BEFORE the membership gate, the E2EE boundary and any
 * media-plane probe or write. Rendered by the
 * [webchat.backend.conferences.api.ConferencesExceptionHandler] of
 * T019 as a typed problem.
 */
class ConferenceChatNotFoundException : RuntimeException("the requested chat does not exist") {
    val code: String = "chat_not_found"
}

/**
 * 403 (api-contract.md 009 №44/№47, FR-003, SC-002): the chat exists
 * but the caller is not its ACTIVE member — a stranger, a №32-kicked
 * or №33-left former member and a never-member answer identically,
 * without disclosing anything about the chat or any conference in it.
 */
class ConferenceForbiddenException : RuntimeException("the caller is not a member of the conference chat") {
    val code: String = "conference_forbidden"
}

/**
 * 409 (api-contract.md 009 №44, FR-001): the v1 boundary with 010 —
 * an E2EE chat (`chats.e2ee`, V18) MUST NOT spawn a conference; the
 * refusal lands BEFORE any media-plane probe or write, so no room and
 * no system invite message are ever created.
 */
class ConferenceE2eeUnsupportedException : RuntimeException("an E2EE chat cannot host a conference in v1") {
    val code: String = "conference_e2ee_unsupported"
}
