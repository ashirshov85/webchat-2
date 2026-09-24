package webchat.backend.sync.api

import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import webchat.backend.chats.domain.service.InvalidUpToSeqException
import webchat.backend.sync.api.dto.SyncCursor
import webchat.backend.sync.api.dto.SyncRequest
import webchat.backend.sync.api.dto.SyncResponse
import webchat.backend.sync.domain.service.ClientCursor
import webchat.backend.sync.domain.service.SyncService
import java.util.UUID

/**
 * The catch-up synchronization endpoint of User Story 1 (T014;
 * api-contract.md №26 `POST /users/me/sync`, FR-003) — a thin HTTP
 * adapter over [SyncService]: the whole sweep (the effective-cursor
 * fold, the candidate read, the per-chat deltas with the truncation
 * and future-cursor repairs, the all-or-refusal snapshot) stays in the
 * service; this layer only resolves the token owner, parses the raw
 * cursors into typed [ClientCursor] commands and judges the two
 * page-limit knobs against the contract bound `1..50` — a violation is
 * the precise `400 limit_out_of_range` of [InvalidSyncLimitException]
 * (`chatLimit`/`messageLimit` by the offending knob), while the
 * defaults themselves flow from `DeliveryProperties` inside the
 * service.
 *
 * The security chain has ALREADY authenticated the request (the same
 * Bearer gate as contract №11 — `401` answers uniformly before any
 * controller code runs); the owner id is the token `sub` claim.
 */
@RestController
@RequestMapping("/api/v1/users/me")
class SyncController(
    private val syncService: SyncService,
) {
    /**
     * Contract №26: `200 SyncResponse` — the deltas of only the chats
     * with undelivered content, latest activity first; an EMPTY chat
     * list is the correct full answer when everything is delivered. A
     * missing `cursors` array folds to an empty map — the sweep then
     * resumes from the server positions alone (the schema carries no
     * minItems on purpose).
     */
    @PostMapping("/sync")
    fun sync(
        @RequestBody request: SyncRequest,
        @AuthenticationPrincipal accessToken: Jwt,
    ): SyncResponse =
        syncService.sync(
            callerId = callerId(accessToken),
            cursors = request.cursors.orEmpty().map(::cursorOf),
            chatLimit = validatedLimit(CHAT_LIMIT_FIELD, request.chatLimit),
            messageLimit = validatedLimit(MESSAGE_LIMIT_FIELD, request.messageLimit),
        )

    private fun callerId(accessToken: Jwt): UUID = UUID.fromString(accessToken.subject)

    /**
     * One raw cursor parsed into the typed №26 command: the `chatId`
     * gate rejects an absent or malformed value as the contract 400
     * `errors: {chatId: [invalid_uuid]}` before the service is touched
     * (a WELL-FORMED but foreign/unknown chatId rides along and is
     * silently ignored inside the sweep — the per-user rule of №26);
     * an absent or negative `upToSeq` (the contract `minimum: 0`) is
     * the reused `400 invalid_up_to_seq` of [InvalidUpToSeqException],
     * never the domain `require` — the controller keeps malformed
     * input out of the service.
     */
    private fun cursorOf(cursor: SyncCursor): ClientCursor =
        ClientCursor(
            chatId = parseChatId(cursor.chatId),
            upToSeq = cursor.upToSeq?.takeIf { it >= MIN_CURSOR } ?: throw InvalidUpToSeqException(),
        )

    /** The №26 knob bound `1..50` judged per field — the same contract constant the service re-asserts. */
    private fun validatedLimit(
        field: String,
        raw: Int?,
    ): Int? {
        if (raw != null && raw !in 1..MAX_PAGE_LIMIT) throw InvalidSyncLimitException(field)
        return raw
    }

    private companion object {
        /** api-contract.md №26: `chatLimit`/`messageLimit` are bound `1..50` (defaults from DeliveryProperties). */
        const val MAX_PAGE_LIMIT = 50

        /** api-contract.md №26 `SyncCursor.upToSeq`: `minimum: 0` — a fresh dialog resumes from zero. */
        const val MIN_CURSOR = 0L

        const val CHAT_LIMIT_FIELD = "chatLimit"
        const val MESSAGE_LIMIT_FIELD = "messageLimit"
    }
}
