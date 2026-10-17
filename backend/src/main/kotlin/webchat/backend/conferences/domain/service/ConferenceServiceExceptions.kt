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

/**
 * 404 (api-contract.md 009 §1/№45–№53): the conferenceId resolves to
 * nothing — OR, for the member reads №45/№46, the caller is not an
 * ACTIVE member of the conference's chat: both answer the SAME uniform
 * `conference_not_found`, so the existence of a conference is never
 * disclosed to a stranger or a №32/№33-former member (the 006 privacy
 * precedent; only №44/№47 split their non-member refusal into the
 * explicit `403 conference_forbidden` the FR-003/US1-3 scenario names).
 * Resolved by the T019 controller on every `{conferenceId}` route.
 */
class ConferenceNotFoundException : RuntimeException("the requested conference does not exist") {
    val code: String = "conference_not_found"
}

/**
 * 409 (api-contract.md 009 №47/№48/№52/№53/№55): the conference row is
 * already `ended` — the «конференция завершена» answer of an old link.
 * Raised BEFORE the mint (T019 №47) and before every mutating
 * moderation/invite leg (T038/T045/T046): nothing is written, the
 * finalized row survives for exactly this answer (Q18=A — the FACT of
 * a conference outlives its roster).
 */
class ConferenceEndedException : RuntimeException("the conference has already been ended") {
    val code: String = "conference_ended"
}

/**
 * 409 (api-contract.md 009 №47/№55, FR-015, Q23=A): the SHARED
 * participant quota of members and guests is exhausted — the mint-time
 * `ListParticipants` pre-check against the conference's
 * `max_participants` snapshot. Raised by
 * [JoinTokenService.mintMember] (T018) BEFORE the principal row and
 * the token: a refused join leaves no trace. A slot freeing up (a
 * participant disconnects) makes the very next №47 succeed — no ban,
 * no cooldown.
 */
class ConferenceFullException : RuntimeException("the conference participant quota is exhausted") {
    val code: String = "conference_full"
}

/**
 * 404 (api-contract.md 009 №49/№50/№51): the moderation target
 * `{identity}` resolves to no principal of the conference — unknown,
 * malformed or never joined, or the conference is already finalized
 * (all rows deleted, Q18=A — indistinguishable by design, the 006
 * privacy precedent). Raised by the moderation legs
 * ([ModerationService], T036/T037) on BOTH the identity-fold and the
 * row-write refusals; the problem+json rendering joins with its T039
 * carrier in
 * [webchat.backend.conferences.api.ConferencesExceptionHandler].
 */
class ParticipantNotFoundException : RuntimeException("the requested conference participant does not exist") {
    val code: String = "participant_not_found"
}
