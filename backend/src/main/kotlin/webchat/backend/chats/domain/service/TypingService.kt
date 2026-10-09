package webchat.backend.chats.domain.service

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import webchat.backend.chats.TypingMetrics
import webchat.backend.chats.domain.model.Chat
import webchat.backend.chats.domain.model.ChatKind
import webchat.backend.chats.domain.model.ChatParticipant
import webchat.backend.chats.domain.port.ParticipantRepository
import webchat.backend.chats.domain.port.RealtimeEventPublisher
import webchat.backend.chats.domain.port.TypingEvent
import webchat.backend.chats.domain.port.TypingStartedEvent
import webchat.backend.chats.domain.port.TypingStoppedEvent
import webchat.backend.chats.domain.port.TypingStore
import webchat.backend.chats.domain.port.TypingState
import webchat.backend.config.ChatsProperties
import webchat.backend.contacts.domain.port.BlockRepository
import java.util.UUID

/**
 * T030 (tasks.md 008a Phase 4, US2): the №41 signal service — the
 * ephemeral typing state machine of data-model.md §2.1 behind the
 * `POST /chats/{chatId}/typing` operation (the api layer of T031):
 *
 *  * the membership gate of every chats resource — [ChatService.get]
 *    answers the №16 refusal order `404 chat_not_found` → `403
 *    not_participant` BEFORE anything is judged, so a stranger (Carrol)
 *    never learns the dialog exists;
 *  * `start` — the state is born/re-armed in the [TypingStore] (ZSET
 *    score = now + `chats.typing.state-ttl`, 8 s) and `typing.started`
 *    is fanned out to the ACTIVE chat participants MINUS the sender on
 *    EVERY valid start including the ~3 s renewals («the indicator does
 *    not sag», AC4) — the anti-flap debounce is the client window + the
 *    60/min flood bucket of T031, never a server-side suppression;
 *  * `stop` — the extinguish leg: [TypingStore.stop] is the atomic
 *    CLAIM (the ZREM itself), and ONLY a claimed — i.e. actively
 *    tracked — state publishes `typing.stopped`; a repeated `stop`
 *    without an active state publishes nothing (the idempotence against
 *    the double extinguish: the client stop of T036, the server-side
 *    extinguish at the typer's own №16 message INSERT of T033 and the
 *    poller's reap of T032 all compete for the SAME removal, and
 *    exactly one of them wins it);
 *  * the DIRECT block-pair suppression (FR-008, the 004 FR-020 parity;
 *    the [ReadService.publishToPeerUnlessBlocked] discipline, BOTH
 *    directions): a blocked pair's signals stay ACCEPTED (`204`,
 *    membership is valid) yet NOTHING is published either way — on
 *    `start` the state is not even created (so the poller can never
 *    later reap — and publish — what the observers never saw), and on
 *    `stop` a state created before the block is still claimed cleanly
 *    while the frame stays suppressed (the observer's 10 s safety
 *    timeout hides the indicator; AC7/AC8 — the observers never see the
 *    block chatter). A GROUP never consults the block marks (the 006
 *    chat.read/message.created parity, FR-008).
 *
 * The frames are fire-and-forget over the at-most-once №18 pipe: the
 * ephemeral state lives only in the shared Redis (SC-003 — zero trace
 * in PG, constitution II — the signal and the observer's stream may
 * ride different pods), nothing is replayed to a (re)connecting client
 * (AC5 — there is no read surface), and a lost frame dies with the
 * observer's 10 s safety timeout, never with a re-send (FR-007).
 */
@Service
// the №41 path collaborators, one port per leg (DIP, plan.md VIII; the MessageService convention)
@Suppress("LongParameterList")
class TypingService(
    private val chatService: ChatService,
    private val participantRepository: ParticipantRepository,
    private val blockRepository: BlockRepository,
    private val typingStore: TypingStore,
    private val realtimeEventPublisher: RealtimeEventPublisher,
    private val chatsProperties: ChatsProperties,
    private val typingMetrics: TypingMetrics,
) {
    private val log = LoggerFactory.getLogger(TypingService::class.java)

    /**
     * The №41 `start` leg: gate → (DIRECT only) the block-pair
     * suppression → the state ZADD (a fresh state creates it, a repeat
     * extends it) → `typing.started` to every ACTIVE participant EXCEPT
     * the sender (own devices never render their own typing — US2 AC9
     * solved by addressing, never by payload flags). A group of one
     * (an empty addressee fold) stores the state but publishes nothing
     * — there is simply nobody to observe it, and the poller reaps the
     * lapse just as silently.
     */
    fun start(
        chatId: UUID,
        senderId: UUID,
    ) {
        val chat = chatService.get(chatId, senderId)
        if (chat.kind == ChatKind.DIRECT && directPairBlocked(chat, senderId)) return
        typingStore.start(chat.id, senderId, chatsProperties.typing.stateTtl)
        publishToAudience(chat.id, senderId, TypingStartedEvent(chatId = chat.id, userId = senderId))
    }

    /**
     * The №41 `stop` leg AND the T033 server-side extinguish at the
     * typer's own №16 message INSERT — one idempotent discipline for
     * both: the [TypingStore.stop] ZREM is the claim, ONLY a claimed
     * state may publish `typing.stopped`, and the DIRECT block pair
     * suppresses the frame exactly as it suppresses `start` (the state
     * itself is still claimed, so the poller never reaps it later).
     */
    fun stop(
        chatId: UUID,
        senderId: UUID,
    ) {
        val chat = chatService.get(chatId, senderId)
        if (!typingStore.stop(chat.id, senderId)) return
        if (chat.kind == ChatKind.DIRECT && directPairBlocked(chat, senderId)) return
        publishToAudience(chat.id, senderId, TypingStoppedEvent(chatId = chat.id, userId = senderId))
    }

    /**
     * The SC-003 self-expiry leg of the T032 poller
     * (TypingTransitionScheduler): the state-ttl horizon lapsed without
     * a single renewal — a hung tab, a killed pod, a metro gap — and
     * [TypingStore.dueExpired] ALREADY claimed the lapse atomically (the
     * ZREM inside the very script), so unlike [stop] there is nothing
     * left to claim here: this method is the publication behind the
     * claim, under the SAME discipline as every other extinguish:
     *
     *  * the №16 gate first — a typer who LEFT or was kicked since his
     *    `start` (or whose chat vanished) answers nothing to anybody:
     *    the refusal is an EXPECTED silent exit here, never a thrown
     *    leg (one poisoned state must not kill the tick's remaining
     *    batch — the claim is already durable), and the observers'
     *    10 s safety timeout hides the stale indicator (FR-007);
     *  * the DIRECT block-pair suppression — a state born BEFORE the
     *    block dies silently exactly like its `stop` twin above (the
     *    observers never see the block chatter, AC7/AC8);
     *  * the self-excluding audience fold of [publishToAudience].
     */
    fun onStateExpired(state: TypingState) {
        val chat = expiredTyperChatOf(state) ?: return
        if (chat.kind == ChatKind.DIRECT && directPairBlocked(chat, state.userId)) return
        publishToAudience(chat.id, state.userId, TypingStoppedEvent(chatId = chat.id, userId = state.userId))
    }

    /**
     * The №16 gate of the poller leg, as a resolve-or-`null`: the two
     * refusals (the chat gone, the typer no longer an active member)
     * are the EXPECTED silent exits of an abandoned state — `null`,
     * never a thrown leg.
     */
    private fun expiredTyperChatOf(state: TypingState): Chat? =
        try {
            chatService.get(state.chatId, state.userId)
        } catch (_: ChatNotFoundException) {
            null
        } catch (_: NotParticipantException) {
            null
        }

    /**
     * FR-008 (AC7): the blocking-pair gate of a DIRECT dialog — the
     * block acts in BOTH directions, checked as two point lookups on
     * the `(blocker_id, blocked_id)` PK pair exactly like the FR-020
     * send gate of [SendPolicyGate.enforceBlockPair], but as a
     * SUPPRESSION, not a refusal: the signal stays accepted `204`, only
     * the publication dies (neither stream may learn about the attempt
     * — the observers never see the block chatter).
     */
    private fun directPairBlocked(
        chat: Chat,
        senderId: UUID,
    ): Boolean {
        val peerId =
            checkNotNull(chat.peerOf(senderId)) {
                "the sender passed the membership gate, so the peer must resolve"
            }
        return blockRepository.exists(senderId, peerId) || blockRepository.exists(peerId, senderId)
    }

    /**
     * The audience of a frame (realtime-events.md 008a §1): the ACTIVE
     * chat participants MINUS the signal sender — one
     * [ParticipantRepository.activeMembers] read serves both kinds (a
     * DIRECT pair is two perpetually `state='active'` rows of V14; a
     * group is its live roster, so a REMOVED former member — already
     * refused by the gate above — can never observe the chat either).
     * An empty fold publishes nothing.
     */
    private fun typingAudience(
        chatId: UUID,
        senderId: UUID,
    ): List<UUID> =
        participantRepository
            .activeMembers(chatId)
            .map(ChatParticipant::userId)
            .filterNot { it == senderId }

    /**
     * At-most-once isolation (the port contract, constitution II/III):
     * the state transition is already durable in the store when this
     * runs, so a lost or failed frame delivery must NOT fail the
     * answered `204` — the observer converges via his 10 s safety
     * timeout (FR-007/FR-008). The [TypingMetrics] sample counts the
     * publication itself, success or failure alike (the leg was handed
     * to the pipe). Warn logs carry ids ONLY (constitution V).
     */
    @Suppress("TooGenericExceptionCaught") // the transport adapter signals any delivery failure by throwing
    private fun publishToAudience(
        chatId: UUID,
        senderId: UUID,
        event: TypingEvent,
    ) {
        val addressees = typingAudience(chatId, senderId)
        if (addressees.isEmpty()) return
        typingMetrics.countEventPublished(event)
        try {
            realtimeEventPublisher.fanoutTypingEvent(addressees, event)
        } catch (failure: Exception) {
            log.warn(
                "realtime fan-out of a typing frame (chat <{}>, typer <{}>, {} addressees) failed; " +
                    "the state is ephemeral and observers hide it by the 10 s safety timeout (FR-007): {}",
                chatId,
                senderId,
                addressees.size,
                failure.message,
            )
        }
    }
}
