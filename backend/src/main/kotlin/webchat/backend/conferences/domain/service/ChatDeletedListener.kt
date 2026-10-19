package webchat.backend.conferences.domain.service

import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import webchat.backend.chats.domain.port.ChatDeletedEvent
import webchat.backend.conferences.domain.model.Conference
import webchat.backend.conferences.domain.model.ConferenceEndReason
import webchat.backend.conferences.domain.port.ConferenceRepository
import webchat.backend.conferences.domain.port.LiveKitAdminClient

/**
 * T062 (009, US5, FR-014) — the «чат удалён» end path of the
 * lifecycle: the IN-PROCESS SYNCHRONOUS consumer of the chats domain's
 * [ChatDeletedEvent], published by the deleting owner's №30 leg
 * ([webchat.backend.groups.domain.service.GroupService.delete])
 * strictly BEFORE the `DELETE FROM chats` statement executes.
 *
 * The seam is the deliberate contrast with the T020 neighbor
 * [GroupMemberRemovedListener] (an `@Async AFTER_COMMIT` listener —
 * the removal is durable by then, only the disconnect follows): THIS
 * leg must run while the deletion transaction is STILL OPEN and the
 * `ON DELETE CASCADE` has NOT fired yet, because everything it needs —
 * the chat's non-ended conference rows and the ACTIVE membership
 * roster — lives in exactly those rows the CASCADE is about to erase
 * (data-model 009 §Сущность 1: «событие домена обрабатывается в
 * транзакции удаления до срабатывания ON DELETE CASCADE — предзапись
 * причины + снимок членства, затем CASCADE удаляет строки»). A plain
 * synchronous [EventListener] delivers exactly that — the listener
 * body runs inside the publisher's transaction, before the DELETE
 * statement; a `BEFORE_COMMIT` transactional phase would NOT: by the
 * time the commit prepares, the statement (and its CASCADE) has long
 * executed, leaving no conference to finalize and no roster to
 * address. And `@Async` is categorically wrong — the finalize has to
 * join the deletion transaction or roll back with it.
 *
 * Per conference (at most one by `ux_conferences_chat_active`; the
 * [ConferenceRepository.findUnendedByChatId] list is the defensive
 * loop shape):
 *
 *  * [ConferenceLifecycleService.finalize] with
 *    [ConferenceEndReason.CHAT_DELETED] — the ONE idempotent seat every
 *    end path funnels into. Its `TransactionTemplate` JOINS this
 *    ambient deletion transaction, so the reason pre-record
 *    (`→ended`, `end_reason='chat_deleted'`), the Q18=A roster/invite
 *    deletions and the membership snapshot commit (or roll back)
 *    TOGETHER with the chat's own death — a rolled-back №30 never
 *    announces anything. The `null` return is the idempotent no-op: a
 *    concurrent end path (№48, the sweeper, a webhook) already won the
 *    conditional UPDATE, its `end_reason` stands and nothing
 *    re-announces (invariant 5);
 *  * the post-commit legs registered on the SAME transaction:
 *    the SSE `conference.ended` rides the lifecycle seat's own
 *    `afterCommit` (the snapshot is the roster at the moment of the
 *    deletion, BEFORE the CASCADE — the frame reaches exactly the
 *    FORMER members, FR-014), and THIS listener adds the media leg —
 *    `DeleteRoom` of the still-live room `c:{id}`, so every live
 *    connection (members and guests alike) is broken server-side the
 *    way №48 breaks them.
 *
 * The `DeleteRoom` leg is best-effort by the committed-row-outranks-
 * the-plane rule (the [ModerationService.end] discipline): the
 * deletion transaction is already committed when it runs, the ended
 * row carried the whole control-plane effect (the CASCADE left no row
 * to re-mint against — every later №45/№47 resolves `404`), and a
 * failing plane converges by itself: the drained room closes by its
 * own empty/departure timeouts. The warn log carries ids only
 * (constitution V).
 */
@Component
class ChatDeletedListener(
    private val conferences: ConferenceRepository,
    private val lifecycle: ConferenceLifecycleService,
    private val liveKit: LiveKitAdminClient,
) {
    private val log = LoggerFactory.getLogger(ChatDeletedListener::class.java)

    /**
     * The one entry point of T062: finalize every non-ended conference
     * of the dying chat inside the deletion transaction, then — once
     * that transaction commits — delete the live room. Idempotent by
     * construction on both legs: an already-ended conference resolves
     * to the finalize no-op (nothing registered, nothing deleted), and
     * `DeleteRoom` of an absent room is the port's success no-op (the
     * SET-semantics of livekit-integration.md §4).
     */
    @EventListener
    fun onChatDeleted(event: ChatDeletedEvent) {
        val running = conferences.findUnendedByChatId(event.chatId)
        if (running.isEmpty()) return
        log.debug(
            "chat <{}> is being deleted with {} running conference(s); finalizing them as chat_deleted",
            event.chatId,
            running.size,
        )
        running.forEach { conference ->
            val ended = lifecycle.finalize(conference, ConferenceEndReason.CHAT_DELETED) ?: return@forEach
            afterCommit { deleteRoomOfEnded(ended) }
        }
    }

    /**
     * The media leg of the №30 delete — strictly AFTER the deletion
     * transaction has committed (the same ordering rule the eager №44
     * `CreateRoom` and №48 `DeleteRoom` follow): the room may still be
     * alive with live member and guest connections, and `DeleteRoom`
     * breaks every one of them server-side. Best-effort: a media-plane
     * failure MUST NOT fail the already-durable deletion — the CASCADE
     * left no conference row, so no re-mint is possible and the drained
     * room converges by its own empty/departure timeouts.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun deleteRoomOfEnded(ended: Conference) {
        try {
            liveKit.deleteRoom(ended.roomName)
        } catch (failure: Exception) {
            log.warn(
                "DeleteRoom of conference <{}> failed after the chat-deletion finalize committed; " +
                    "no re-mint is possible and the drained room closes by its empty/departure timeout: {}",
                ended.id,
                failure.message,
            )
        }
    }

    /**
     * The post-commit ordering rule (the `afterCommit` discipline of
     * the chats/group services, verbatim): the room deletion runs in
     * `afterCommit` of the AMBIENT deletion transaction — a rolled-back
     * №30 deletes no room. Without an active synchronization (a direct
     * unit call) the deletion runs inline.
     */
    private fun afterCommit(deleteRoom: () -> Unit) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            deleteRoom()
            return
        }
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() = deleteRoom()
            },
        )
    }
}
