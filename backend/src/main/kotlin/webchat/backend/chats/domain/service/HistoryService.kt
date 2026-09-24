package webchat.backend.chats.domain.service

import org.springframework.stereotype.Service
import webchat.backend.chats.domain.model.Message
import webchat.backend.chats.domain.port.MessageRepository
import webchat.backend.config.ChatsProperties
import java.util.UUID

/**
 * 400 (api-contract.md №15): the `limit` query is outside the contract
 * bounds `1..50` — the bound is FIXED by the public contract and mirrored
 * by `chats.message.page-size` (FR-008), never a free choice of the caller.
 * The code is carried for the problem+json rendering of the api layer
 * (T017).
 */
class LimitOutOfRangeException : RuntimeException("limit must be within 1..chats.message.page-size") {
    val code: String = "limit_out_of_range"
}

/**
 * 400 (api-contract.md 005 §2): the two №15 cursors arrived together —
 * `after` AND `before` in one request. The two walk directions are
 * mutually exclusive (an ambiguous page could skip or duplicate rows),
 * so the refusal is explicit rather than a silently-prioritized
 * direction; the code is carried for the problem+json rendering of the
 * api layer (`errors: {after: [mixed_cursors]}`).
 */
class MixedCursorsException : RuntimeException("after and before are mutually exclusive cursors") {
    val code: String = "mixed_cursors"
}

/**
 * The №15 answer of User Story 1 (T015): [messages] ordered `seq DESC` —
 * exactly as the visibility query returns them — and [nextBefore], the
 * EXCLUSIVE cursor of the next older page, absent at exhaustion. The api
 * layer (T017) maps this to the contract `MessagePage`.
 *
 * 005 §2 additive extension: the ascending `after` mode fills the
 * mirror cursor [nextAfter] instead — the seq of the LAST (newest) row
 * of its page — while [nextBefore] stays null; the two cursors are
 * mode-specific and never coexist in one answer.
 */
data class HistoryPage(
    val messages: List<Message>,
    val nextBefore: Long? = null,
    val nextAfter: Long? = null,
)

/**
 * History read of User Story 1 (T015; api-contract.md №15, FR-008,
 * research.md 004 §3): the latest page plus the `before=<seq>` walk to
 * the beginning of the dialog.
 *
 * Every history request resolves through the same FR-002 membership gate
 * as the rest of the chats resources (`404 chat_not_found` →
 * `403 not_participant`, via [ChatService.get]) — before any parameter
 * or repository work. The cursor is EXCLUSIVE (`seq < before`) and is
 * passed straight to the visibility query; per-user visibility itself
 * (`seq > deleted_up_to_seq`, FR-021) belongs to the repository port
 * (T012), so both participants page over the SAME server order (FR-006).
 *
 * `nextBefore` derivation: a page SHORTER than the requested limit is
 * the boundary — nothing older is visible, so no cursor is returned and
 * the client stops (US3-4); an exact-multiple history surfaces its true
 * end one empty page later, which is a correct answer, not an error.
 * The visible set behind a deletion watermark is a contiguous suffix
 * (data-model 004 §2), so a short page can never be followed by more
 * rows — the `size == limit` check is exact.
 *
 * 005 §2 (T015, additive): the EXCLUSIVE `after` cursor (`int64 ≥ 0`)
 * flips the read into the ascending catch-up mode — the replay window
 * `(after, after+limit]` by `seq ASC` through
 * [MessageRepository.findVisiblePageAfter] (sync-protocol.md §4, the
 * cycle-B fetch of the catch-up synchronization). The SAME membership
 * gate, visibility rule and limit bound apply; `nextAfter` mirrors the
 * `nextBefore` derivation on the newest row of a full page, an empty or
 * short page at the head is a CLEAN answer (the sync loop stops on the
 * missing cursor, never on an error), and the two cursors together are
 * the [MixedCursorsException] refusal.
 */
@Service
class HistoryService(
    private val chatService: ChatService,
    private val messageRepository: MessageRepository,
    private val chatsProperties: ChatsProperties,
) {
    fun history(
        chatId: UUID,
        viewerId: UUID,
        before: Long?,
        after: Long?,
        limit: Int?,
    ): HistoryPage {
        chatService.get(chatId, viewerId)
        if (before != null && after != null) throw MixedCursorsException()
        val pageSize = chatsProperties.message.pageSize
        val effectiveLimit = limit ?: pageSize
        if (effectiveLimit !in 1..pageSize) throw LimitOutOfRangeException()
        return if (after != null) {
            val messages = messageRepository.findVisiblePageAfter(chatId, viewerId, after, effectiveLimit)
            HistoryPage(
                messages = messages,
                nextBefore = null,
                nextAfter = if (messages.size == effectiveLimit) messages.last().seq else null,
            )
        } else {
            val messages = messageRepository.findVisiblePage(chatId, viewerId, before, effectiveLimit)
            HistoryPage(
                messages = messages,
                nextBefore = if (messages.size == effectiveLimit) messages.last().seq else null,
            )
        }
    }
}
