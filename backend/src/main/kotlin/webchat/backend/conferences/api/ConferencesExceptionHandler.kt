package webchat.backend.conferences.api

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import webchat.backend.conferences.api.dto.InvalidConferenceModeException
import webchat.backend.conferences.domain.port.ConferenceMediaUnavailableException
import webchat.backend.conferences.domain.service.ConferenceChatNotFoundException
import webchat.backend.conferences.domain.service.ConferenceE2eeUnsupportedException
import webchat.backend.conferences.domain.service.ConferenceEndedException
import webchat.backend.conferences.domain.service.ConferenceForbiddenException
import webchat.backend.conferences.domain.service.ConferenceFullException
import webchat.backend.conferences.domain.service.ConferenceNotFoundException

/**
 * T019 — RFC 9457 rendering for the conferences API errors
 * (api-contract.md 009 §1–§3, the same conventions as the chats
 * [webchat.backend.chats.api.ChatsExceptionHandler]): every failure
 * leaves the controller as a typed exception and reaches the client as
 * `application/problem+json` with the contract code inside `errors:
 * map<string, string[]>` — codes and field names only, never chat
 * contents, roster details or conference ids (constitution V).
 *
 * The table this handler pins (one @ExceptionHandler per contract
 * failure code of the №44–№47 surface of T019; the moderation codes
 * `403 moderator_required` / `404 participant_not_found` /
 * `404 guest_invite_not_found` join with their T039/T045 carriers):
 * `404 chat_not_found` on the `chat` field, the conferences family on
 * the `conference` field (`403 conference_forbidden`, `404
 * conference_not_found`, `409 conference_e2ee_unsupported |
 * conference_ended | conference_full`, `503
 * conference_media_unavailable`) and the request-shape 400s
 * (`mode: [invalid_mode]`, `chatId`/`conferenceId: [invalid_uuid]`).
 */
@Suppress("TooManyFunctions") // one @ExceptionHandler per contract failure code — the №44–№47 table drives it
@RestControllerAdvice
class ConferencesExceptionHandler {
    /** 404 (api-contract.md 009 №44): the chatId of the create call resolves to nothing. */
    @ExceptionHandler(ConferenceChatNotFoundException::class)
    fun onConferenceChatNotFound(): ProblemDetail =
        problem(HttpStatus.NOT_FOUND, CHAT_NOT_FOUND_DETAIL)
            .apply { setProperty(ERRORS_PROPERTY, mapOf(CHAT_FIELD to listOf(CHAT_NOT_FOUND_CODE))) }

    /**
     * 403 (api-contract.md 009 №44/№47, FR-003, SC-002): the chat exists
     * but the caller is not its ACTIVE member — a stranger, a
     * №32-kicked or №33-left former member and a never-member answer
     * identically, without disclosing anything about the chat or any
     * conference in it.
     */
    @ExceptionHandler(ConferenceForbiddenException::class)
    fun onConferenceForbidden(): ProblemDetail =
        problem(HttpStatus.FORBIDDEN, FORBIDDEN_DETAIL)
            .apply { setProperty(ERRORS_PROPERTY, mapOf(CONFERENCE_FIELD to listOf(FORBIDDEN_CODE))) }

    /**
     * 404 (api-contract.md 009 §1, №45–№53): the uniform member-read
     * refusal — an unknown conferenceId and a non-member's conferenceId
     * answer the SAME code, so the existence of a conference is never
     * disclosed (the 006 privacy precedent).
     */
    @ExceptionHandler(ConferenceNotFoundException::class)
    fun onConferenceNotFound(): ProblemDetail =
        problem(HttpStatus.NOT_FOUND, NOT_FOUND_DETAIL)
            .apply { setProperty(ERRORS_PROPERTY, mapOf(CONFERENCE_FIELD to listOf(NOT_FOUND_CODE))) }

    /**
     * 409 (api-contract.md 009 №44, FR-001): the v1 boundary with 010 —
     * an E2EE chat (`chats.e2ee`, V18) must not spawn a conference; the
     * refusal landed before any media-plane probe or write, so no room
     * and no system invite message exist.
     */
    @ExceptionHandler(ConferenceE2eeUnsupportedException::class)
    fun onConferenceE2eeUnsupported(): ProblemDetail =
        problem(HttpStatus.CONFLICT, E2EE_UNSUPPORTED_DETAIL)
            .apply { setProperty(ERRORS_PROPERTY, mapOf(CONFERENCE_FIELD to listOf(E2EE_UNSUPPORTED_CODE))) }

    /**
     * 409 (api-contract.md 009 №47/№48/№52/№53/№55): the «конференция
     * завершена» refusal of an old link — the row is already `ended`;
     * nothing was written.
     */
    @ExceptionHandler(ConferenceEndedException::class)
    fun onConferenceEnded(): ProblemDetail =
        problem(HttpStatus.CONFLICT, ENDED_DETAIL)
            .apply { setProperty(ERRORS_PROPERTY, mapOf(CONFERENCE_FIELD to listOf(ENDED_CODE))) }

    /**
     * 409 (api-contract.md 009 №47/№55, FR-015, Q23=A): the SHARED
     * participant quota of members and guests is exhausted — the
     * mint-time `ListParticipants` pre-check; the refused join left no
     * principal row and no token.
     */
    @ExceptionHandler(ConferenceFullException::class)
    fun onConferenceFull(): ProblemDetail =
        problem(HttpStatus.CONFLICT, FULL_DETAIL)
            .apply { setProperty(ERRORS_PROPERTY, mapOf(CONFERENCE_FIELD to listOf(FULL_CODE))) }

    /**
     * 503 (api-contract.md 009 №44/№47): the media plane is UNREACHABLE
     * past the adapter's retries (the №44 pre-flight probe, the №47
     * mint-time roster read) — an explicit temporary refusal, never a
     * silent one; the control plane and the rest of the API stay
     * untouched, and the triggering operation wrote nothing.
     */
    @ExceptionHandler(ConferenceMediaUnavailableException::class)
    fun onConferenceMediaUnavailable(): ProblemDetail =
        problem(HttpStatus.SERVICE_UNAVAILABLE, MEDIA_UNAVAILABLE_DETAIL)
            .apply { setProperty(ERRORS_PROPERTY, mapOf(CONFERENCE_FIELD to listOf(MEDIA_UNAVAILABLE_CODE))) }

    /** 400 (api-contract.md 009 №44): `mode` is absent or outside `video|audio`. */
    @ExceptionHandler(InvalidConferenceModeException::class)
    fun onInvalidConferenceMode(): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, INVALID_MODE_DETAIL)
            .apply { setProperty(ERRORS_PROPERTY, mapOf(MODE_FIELD to listOf(INVALID_MODE_CODE))) }

    /** 400 (api-contract.md 009 №44): the PATH `chatId` is not a UUID. */
    @ExceptionHandler(InvalidConferenceChatIdException::class)
    fun onInvalidConferenceChatId(): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, INVALID_CHAT_ID_DETAIL)
            .apply { setProperty(ERRORS_PROPERTY, mapOf(CHAT_ID_FIELD to listOf(INVALID_UUID_CODE))) }

    /** 400 (api-contract.md 009 №45/№46/№47): the PATH `conferenceId` is not a UUID. */
    @ExceptionHandler(InvalidConferenceIdException::class)
    fun onInvalidConferenceId(): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, INVALID_CONFERENCE_ID_DETAIL)
            .apply { setProperty(ERRORS_PROPERTY, mapOf(CONFERENCE_ID_FIELD to listOf(INVALID_UUID_CODE))) }

    private fun problem(
        status: HttpStatus,
        detail: String,
    ): ProblemDetail =
        ProblemDetail.forStatus(status).apply {
            title = status.reasonPhrase
            this.detail = detail
        }

    private companion object {
        const val ERRORS_PROPERTY = "errors"
        const val CHAT_FIELD = "chat"
        const val CHAT_ID_FIELD = "chatId"
        const val CONFERENCE_FIELD = "conference"
        const val CONFERENCE_ID_FIELD = "conferenceId"
        const val MODE_FIELD = "mode"
        const val CHAT_NOT_FOUND_CODE = "chat_not_found"
        const val FORBIDDEN_CODE = "conference_forbidden"
        const val NOT_FOUND_CODE = "conference_not_found"
        const val E2EE_UNSUPPORTED_CODE = "conference_e2ee_unsupported"
        const val ENDED_CODE = "conference_ended"
        const val FULL_CODE = "conference_full"
        const val MEDIA_UNAVAILABLE_CODE = "conference_media_unavailable"
        const val INVALID_MODE_CODE = "invalid_mode"
        const val INVALID_UUID_CODE = "invalid_uuid"
        const val CHAT_NOT_FOUND_DETAIL = "The requested chat does not exist"
        const val FORBIDDEN_DETAIL = "The caller is not a member of the conference chat"
        const val NOT_FOUND_DETAIL = "The requested conference does not exist or is not available to the caller"
        const val E2EE_UNSUPPORTED_DETAIL = "An E2EE chat cannot host a conference in v1 (FR-001)"
        const val ENDED_DETAIL = "The conference has already been ended"
        const val FULL_DETAIL = "The conference participant quota is exhausted"
        const val MEDIA_UNAVAILABLE_DETAIL =
            "The media plane is temporarily unavailable; the operation wrote nothing (conference_media_unavailable)"
        const val INVALID_MODE_DETAIL = "mode must be one of: video, audio"
        const val INVALID_CHAT_ID_DETAIL = "chatId must be a UUID"
        const val INVALID_CONFERENCE_ID_DETAIL = "conferenceId must be a UUID"
    }
}
