package webchat.backend.chats.domain.service

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import webchat.backend.backpressure.AdmissionDecision
import webchat.backend.backpressure.AdmissionLease
import webchat.backend.backpressure.SendAdmissionGate
import webchat.backend.chats.domain.model.Chat
import webchat.backend.chats.domain.model.ChatKind
import webchat.backend.chats.domain.model.ChatParticipant
import webchat.backend.chats.domain.model.Message
import webchat.backend.chats.domain.model.MessageText
import webchat.backend.chats.domain.port.MessageCreatedEvent
import webchat.backend.chats.domain.port.MessageInsertResult
import webchat.backend.chats.domain.port.MessageRepository
import webchat.backend.chats.domain.port.NewMessage
import webchat.backend.chats.domain.port.ParticipantRepository
import webchat.backend.chats.domain.port.RealtimeEventPublisher
import webchat.backend.config.ChatsProperties
import webchat.backend.groups.GroupMetrics
import java.util.UUID

/**
 * 409 (api-contract.md №16): the client-supplied `clientMessageId` already
 * belongs to a DIFFERENT stored record (a foreign id or a cross-chat retry)
 * — the send is refused and nothing is written.
 */
class MessageIdConflictException : RuntimeException("the clientMessageId belongs to another stored message") {
    val code: String = "message_id_conflict"
}

/**
 * 429 (api-contract.md №16, FR-011): the per-user send flood limit is
 * exhausted — [retryAfterSeconds] is the integral ceiling of the wait for
 * the next available token, rendered by the api layer as the
 * `Retry-After` header. Nothing is written by a refused send.
 */
class FloodLimitException(
    val retryAfterSeconds: Long,
) : RuntimeException("the per-user send flood limit is exhausted") {
    val code: String = "flood_limit"
}

/**
 * 503 (api-contract.md 005 §3, FR-009/FR-013, T041): the per-instance
 * admission control SHED this send attempt — the path is loaded beyond
 * its adaptive limit. [retryAfterSeconds] is the integral drain estimate
 * of research.md 005 §6 (`ceil(in-flight × EWMA / max(limit, 1))`,
 * floored at 1), rendered by the api layer as the `Retry-After` header
 * of the `503 server_busy` problem+json. A shed writes NOTHING and burns
 * no flood token (FR-010) — the system signal stays ahead of the
 * personal limit, and the shed send retries idempotently by the same
 * `clientMessageId` (US4: the client parks it in the transparent queue).
 */
class ServerBusyException(
    val retryAfterSeconds: Long,
) : RuntimeException("the send path is overloaded beyond its admission limit") {
    val code: String = "server_busy"
}

/**
 * 403 (api-contract.md №16, FR-020): the SENDER blocks the recipient —
 * the refusal the blocker himself sees («вы заблокировали получателя»).
 * Rejected BEFORE the INSERT, so no record and no publication exist.
 */
class ChatBlockedByYouException : RuntimeException("the caller blocks the peer of this chat (FR-020)") {
    val code: String = "chat_blocked_by_you"
}

/**
 * 403 (api-contract.md №16, FR-020): the RECIPIENT blocks the sender —
 * the explicit notification of the blocked user, his ONLY way to learn
 * about the block (the API carries no inverse block field). Rejected
 * BEFORE the INSERT, so no record and no publication exist.
 */
class YouAreBlockedException : RuntimeException("the peer of this chat blocks the caller (FR-020)") {
    val code: String = "you_are_blocked"
}

/**
 * The outcome of a send for the api layer (T017): [Created] maps to
 * `201 Message` (a fresh durable record), [Existing] maps to `200 Message`
 * (the FR-004 idempotent retry — the same row, never a duplicate).
 */
sealed interface MessageSendResult {
    val message: Message

    data class Created(
        override val message: Message,
    ) : MessageSendResult

    data class Existing(
        override val message: Message,
    ) : MessageSendResult
}

/**
 * The send path of the exactly-once core (data-model 004 §3 «Запись»):
 *
 *  0. the dedup fast-path (T031) — a lookup by the client UUID BEFORE the
 *     membership gate, the limits and the locks: a retry of an
 *     already-recorded message resolves to the SAME stored row (`200`),
 *     so it can never be flood-penalized (T032) nor blocked (T054) — the
 *     retry always converges (US2-1/2);
 *  0b. the admission gate (T041; api-contract.md 005 §3, FR-009/FR-013) —
 *     STRICTLY after the dedup fast-path and BEFORE the membership,
 *     validation and flood legs: a retry of an already recorded id has
 *     already resolved through the dedup and never reaches the gate, so
 *     it can never be shed; a shed ([AdmissionDecision.Shed] →
 *     [ServerBusyException] → `503 server_busy` + `Retry-After`) writes
 *     nothing and burns no flood token (FR-010), and an admitted send
 *     holds its in-flight lease ([AdmissionDecision.Admitted.lease]) for
 *     the synchronous send leg — settled exactly once via `use`, with
 *     [webchat.backend.backpressure.AdmissionLease.acked] feeding the
 *     `201`/`200` hold into the limiter's latency signal only;
 *  1. membership (FR-002, T014) → FR-003 text normalization → the
 *     per-user flood bucket (FR-011, T032: `rl:user:msgsend:{userId}`,
 *     30 tokens/min in Redis — AFTER the dedup, BEFORE the INSERT, so a
 *     refused send consumes its token but writes nothing) → the FR-020
 *     blocking-pair gate (T054: BOTH directions checked before the
 *     INSERT) → the exactly-once INSERT (`ON CONFLICT`, owned by
 *     [MessageRepository.insert]) → `201` strictly after the PG commit →
 *     `message.created` published to `rt:user:{sender}` AND
 *     `rt:user:{recipient}` AFTER that commit (FR-004 basis, FR-007).
 *
 * The race leg of the dedup (an empty `RETURNING` of parallel retries)
 * stays owned by the repository: it SELECTs the winning row and reports
 * [MessageInsertResult.Duplicate], mapped here to the same `200`/`409`
 * rules as the fast-path.
 *
 * This service is deliberately NOT `@Transactional`:
 * [MessageRepository.insert] owns the single PG transaction (T012), and it
 * is already COMMITTED when it returns — so the publication below is
 * strictly post-commit (data-model 004 §3 step 5; a pre-commit publish
 * could announce a record that a rollback then erases). Wrapping `send`
 * in a transaction would break the FR-005/FR-007 ordering guarantee.
 *
 * Later stories insert their gates into this path without restructuring
 * it: the blocking-pair refusals (T054) — AFTER the dedup fast-path and
 * between the flood bucket and the INSERT — are in place: a retry of an
 * already recorded message resolves through the dedup FIRST and is never
 * blocked (FR-020 edge), while every fresh send into a blocked pair is
 * refused before any record with `403 chat_blocked_by_you` /
 * `403 you_are_blocked` and publishes nothing into either stream. The
 * admission gate (T041) joins the same way `SendPolicyGate` did in 004 —
 * injected from the outside (DIP, plan.md VIII), one port call on the
 * path. The delivery legs BEYOND the gate stay ungated («принятые
 * доставляются в приоритете», US4-4): the realtime fan-out below and the
 * №26 pull synchronization run without admission — overload may only
 * slow ACCEPTANCE, never the delivery of accepted messages.
 *
 * T035 (006, US2, FR-011): a `kind='group'` chat rides the SAME gated
 * path with exactly two kind-specific substitutions, everything else —
 * the dedup fast-path, the admission gate, the flood bucket, the
 * exactly-once INSERT and the `201`/`200`/`409` mapping — unchanged:
 *  * the FR-020 blocking-pair gate is a DIRECT-dialog concept — a group
 *    send NEVER consults the block marks (spec.md Assumptions: a pair
 *    blocked in their dialog keeps exchanging group messages);
 *  * the post-commit publication addresses the ACTIVE-roster snapshot
 *    MINUS the sender (realtime-group-events.md §3.7) — the snapshot is
 *    read as the last statement before the INSERT (the roster under
 *    which the message is admitted; the group scenario transactions of
 *    006 serialize roster changes on the `chats` row lock, and a roster
 *    race outside that window converges client-side: an at-most-once
 *    extra frame past a removal is ignored after `group.you_removed`
 *    (§1), a missed one is replayed by the №26 catch-up, FR-009) — and
 *    rides the list leg [RealtimeEventPublisher.fanoutMessageCreated]
 *    with the [GroupMetrics] fan-out pair of T010
 *    (`webchat_group_message_fanout_total`/`_seconds`).
 *
 * T033 (008a, US2, data-model.md §2.1 transition 2): the typer's own
 * successful №16 INSERT extinguishes his ephemeral typing state — the
 * isolated [extinguishTypingAtSend] leg rides strictly AFTER the
 * durable ack and reuses the idempotent claim+publish discipline of
 * [TypingService.stop]: exactly ONE of the client `stop` (T036), this
 * INSERT leg and the poller reap (T032) may publish `typing.stopped`,
 * the losers find the claim empty and stay silent. A send REFUSED
 * before the INSERT (blank text, flood, block) never reaches the leg —
 * that stop belongs to the client alone (the spec edge: a rejected
 * draft keeps the state while the typer goes on typing), and a №16
 * resolution that wrote nothing (both `200` dedup legs) skips it too:
 * the racing winner of the very row has already claimed.
 */
@Service
// the №16 path collaborators, one per leg
// (T041 the admission gate; T035 the 006 roster/metrics pair; T033 the 008a typing extinguish)
@Suppress("LongParameterList")
class MessageService(
    private val chatService: ChatService,
    private val messageRepository: MessageRepository,
    private val realtimeEventPublisher: RealtimeEventPublisher,
    private val chatsProperties: ChatsProperties,
    private val sendPolicyGate: SendPolicyGate,
    private val sendAdmissionGate: SendAdmissionGate,
    private val meterRegistry: MeterRegistry,
    private val participantRepository: ParticipantRepository,
    private val groupMetrics: GroupMetrics,
    private val typingService: TypingService,
) {
    private val log = LoggerFactory.getLogger(MessageService::class.java)

    fun send(
        chatId: UUID,
        senderId: UUID,
        clientMessageId: UUID,
        rawText: String,
    ): MessageSendResult {
        val ack = Timer.start(meterRegistry)
        resolveDedupFastPath(chatId, senderId, clientMessageId)?.let { recorded ->
            dedupTotal().increment()
            ack.stop(ackTimer(OUTCOME_EXISTING))
            return MessageSendResult.Existing(recorded)
        }
        // T041 (api-contract.md 005 §3): the admission gate joins the path
        // exactly here — AFTER the dedup fast-path (a retry never reaches
        // it, FR-009), BEFORE membership/validation/flood (a shed is the
        // system signal outranking every per-request refusal). The lease
        // settles exactly once via `use` on every outcome, and only the
        // durable `201`/`200` outcomes feed it a latency sample (acked).
        return when (val decision = sendAdmissionGate.admit(chatId, senderId)) {
            is AdmissionDecision.Shed -> throw shedRefusal(chatId, senderId, decision.retryAfterSeconds)
            is AdmissionDecision.Admitted ->
                decision.lease.use { lease ->
                    executeAdmittedSend(ack, lease, chatId, senderId, clientMessageId, rawText)
                }
        }
    }

    /**
     * T041 (SC-005): the warn carrier of a shed — ids only, never the
     * message text (constitution V, the PII minimization of 004); the
     * refusal itself leaves to the api layer as the typed
     * [ServerBusyException] rendered `503 server_busy` + `Retry-After`.
     */
    private fun shedRefusal(
        chatId: UUID,
        senderId: UUID,
        retryAfterSeconds: Long,
    ): ServerBusyException {
        log.warn(
            "send admission shed: chat <{}>, sender <{}>, retry after <{}> s " +
                "(503 server_busy, SC-005; no row written, no flood token burned)",
            chatId,
            senderId,
            retryAfterSeconds,
        )
        return ServerBusyException(retryAfterSeconds)
    }

    /**
     * The gated remainder of the №16 path (steps 1+ of the class doc):
     * membership → normalization → flood → the KIND branch (a direct
     * dialog: the FR-020 blocking pair; a group: the FR-011 addressee
     * snapshot) → INSERT → the post-commit fan-out of the same kind. The
     * [webchat.backend.backpressure.AdmissionLease] settles at the `use`
     * of the caller on EVERY outcome; only the durable `201 Created` /
     * `200 Existing` settles mark [AdmissionLease.acked] — a typed
     * refusal thrown here releases its slot WITHOUT a latency sample (a
     * fast 4xx/429 never measured the PG-commit leg).
     */
    private fun executeAdmittedSend(
        ack: Timer.Sample,
        lease: AdmissionLease,
        chatId: UUID,
        senderId: UUID,
        clientMessageId: UUID,
        rawText: String,
    ): MessageSendResult {
        val chat = chatService.get(chatId, senderId)
        val text = MessageText.normalize(rawText, chatsProperties.message.maxLength)
        sendPolicyGate.enforceFloodLimit(senderId)
        // T035 (006): the FR-020 blocking pair is a DIRECT concept —
        // block marks never apply to a group (spec.md Assumptions); the
        // group branch reads its FR-011 addressee snapshot instead, the
        // ACTIVE roster minus the sender, as the LAST statement before
        // the INSERT (the class doc carries the convergence argument).
        val groupAddressees: List<UUID>? =
            when (chat.kind) {
                ChatKind.DIRECT -> {
                    sendPolicyGate.enforceBlockPair(chat, senderId)
                    null
                }
                ChatKind.GROUP ->
                    participantRepository
                        .activeMembers(chat.id)
                        .map(ChatParticipant::userId)
                        .filterNot { it == senderId }
            }
        val outcome =
            messageRepository.insert(
                NewMessage(id = clientMessageId, chatId = chat.id, senderId = senderId, text = text),
            )
        return when (outcome) {
            is MessageInsertResult.Inserted -> {
                when (chat.kind) {
                    ChatKind.DIRECT -> publishCreated(chat, outcome.message)
                    ChatKind.GROUP ->
                        publishGroupCreated(
                            chatId = chat.id,
                            message = outcome.message,
                            addressees =
                                checkNotNull(groupAddressees) {
                                    "a group send resolves its roster snapshot before the INSERT"
                                },
                        )
                }
                ack.stop(ackTimer(OUTCOME_CREATED))
                lease.acked()
                // T033: strictly after the durable ack — the ephemeral
                // typing leg never delays nor pollutes the SC-001 ack
                // sample, and its failure can never fail the record.
                extinguishTypingAtSend(chat.id, senderId)
                MessageSendResult.Created(outcome.message)
            }
            is MessageInsertResult.Duplicate -> {
                val existing = outcome.existing
                if (existing.chatId != chat.id || existing.senderId != senderId) {
                    throw MessageIdConflictException()
                }
                dedupTotal().increment()
                ack.stop(ackTimer(OUTCOME_EXISTING))
                lease.acked()
                MessageSendResult.Existing(existing)
            }
        }
    }

    /**
     * T033 (008a, data-model.md §2.1 transition 2): the typer's own
     * successful №16 INSERT extinguishes his typing state — one more
     * competitor for the SAME atomic claim as the client `stop` of T036
     * and the poller reap of T032; [TypingService.stop] owns the whole
     * discipline (the claim itself, the №16 gate, the DIRECT block-pair
     * suppression and the self-excluding `typing.stopped` fan-out), so
     * a typer who was never typing costs one silent ZREM miss. Runs
     * strictly AFTER the durable ack: the record is already answered,
     * so this ephemeral leg is at-most-once isolated — a failure here
     * never fails the send, and the un-claimed state dies by its TTL
     * horizon and the poller reap anyway (SC-003; the observers' 10 s
     * safety timeout hides the stale indicator in the meantime). Warn
     * logs carry ids ONLY (constitution V).
     */
    @Suppress("TooGenericExceptionCaught") // at-most-once isolation of the ephemeral leg behind the durable ack
    private fun extinguishTypingAtSend(
        chatId: UUID,
        senderId: UUID,
    ) {
        try {
            typingService.stop(chatId, senderId)
        } catch (failure: Exception) {
            log.warn(
                "typing extinguish at the durable send of user <{}> in chat <{}> failed; " +
                    "the record stands and the state dies by its TTL/poller reap (SC-003): {}",
                senderId,
                chatId,
                failure.message,
            )
        }
    }

    /**
     * data-model 004 §3 step 0 (research.md 004 §3): the dedup fast-path —
     * the lookup by the client UUID runs BEFORE the membership gate, the
     * limits and the locks, so a retry of an already-recorded message
     * always converges to the same stored row (US2-1/2): the flood bucket
     * (T032) and the blocking-pair refusals (T054) can never interfere
     * with it.
     *
     * A found row is compared to the request by `chat_id`/`sender_id`
     * ONLY — the draft text is never compared nor rewritten (the id, not
     * the draft, is the deduplication key); a mismatch is the
     * `409 message_id_conflict` of №16 (a foreign id). A miss returns
     * `null` and the send continues down the gated path.
     */
    private fun resolveDedupFastPath(
        chatId: UUID,
        senderId: UUID,
        clientMessageId: UUID,
    ): Message? {
        val recorded = messageRepository.findById(clientMessageId) ?: return null
        if (recorded.chatId != chatId || recorded.senderId != senderId) throw MessageIdConflictException()
        return recorded
    }

    /**
     * FR-007 fan-out to BOTH participants of the DIRECT dialog
     * (realtime-channel.md §3.1; the 004 leg, unchanged by 006): the
     * recipient renders the message; the sender's other devices/sessions
     * render it already «доставлено» (US2-6). Only an ACTUAL new record
     * publishes — the idempotent `200` retry is silent (at-most-once
     * channel + exactly-once storage). The group counterpart of 006 is
     * [publishGroupCreated] (T035).
     *
     * Each target is isolated: the record is durable and the request
     * already succeeded, so a lost/failed realtime delivery must NOT fail
     * it — clients converge via the refetch on (re)connect (FR-009). Warn
     * logs carry ids only, never the message text (constitution V,
     * SC-008).
     */
    private fun publishCreated(
        chat: Chat,
        message: Message,
    ) {
        val event = MessageCreatedEvent(chatId = chat.id, message = message)
        val recipientId =
            checkNotNull(chat.peerOf(message.senderId)) {
                "the sender passed the membership gate, so the peer must resolve"
            }
        publishIsolated(message.senderId, event)
        publishIsolated(recipientId, event)
    }

    /**
     * T035 (006, FR-011, realtime-group-events.md §3.7): the group
     * fan-out — `message.created` to EVERY [addressees] entry (the ACTIVE
     * roster snapshot MINUS the sender, resolved before the INSERT) over
     * the list leg [RealtimeEventPublisher.fanoutMessageCreated], with
     * the payload of the №16 answer VERBATIM (the exact 004 frame shape;
     * clients dedup by `message.id`). The sender's own stream stays
     * silent — unlike a direct dialog, his devices already hold the
     * durable answer of this very POST.
     *
     * The [GroupMetrics] pair of T010 rides the loop:
     * `webchat_group_message_fanout_seconds` times the publish and
     * `webchat_group_message_fanout_total` grows by the leg count. The
     * same at-most-once isolation as the direct leg holds — the record is
     * durable and the request already succeeded, so a lost/failed frame
     * must NOT fail it (clients converge via the №26 catch-up, FR-009);
     * the transport adapter isolates per-addressee failures itself, the
     * catch here is the defense-in-depth of the same discipline. Warn
     * logs carry ids only, never the message text (constitution V,
     * SC-008).
     */
    @Suppress("TooGenericExceptionCaught") // at-most-once isolation, the same defense-in-depth as the direct leg
    private fun publishGroupCreated(
        chatId: UUID,
        message: Message,
        addressees: List<UUID>,
    ) {
        val event = MessageCreatedEvent(chatId = chatId, message = message)
        val fanout = groupMetrics.startFanout()
        try {
            realtimeEventPublisher.fanoutMessageCreated(addressees, event)
        } catch (failure: Exception) {
            log.warn(
                "realtime fan-out of message <{}> in group <{}> to <{}> addressees failed; " +
                    "the record is durable and clients converge on the №26 catch-up (FR-009): {}",
                message.id,
                chatId,
                addressees.size,
                failure.message,
            )
        } finally {
            groupMetrics.countFanoutLegs(addressees.size)
            fanout.stop()
        }
    }

    @Suppress("TooGenericExceptionCaught") // the transport adapter signals any delivery failure by throwing
    private fun publishIsolated(
        targetUserId: UUID,
        event: MessageCreatedEvent,
    ) {
        try {
            realtimeEventPublisher.publishMessageCreated(targetUserId, event)
        } catch (failure: Exception) {
            log.warn(
                "realtime fan-out of message <{}> in chat <{}> to user <{}> failed; " +
                    "the record is durable and clients converge on refetch (FR-009): {}",
                event.message.id,
                event.chatId,
                targetUserId,
                failure.message,
            )
        }
    }

    /**
     * T037 (research.md 004 §11, SC-008): `webchat_message_dedup_total`
     * counts every send that resolved through the dedup — BOTH legs: the
     * T031 fast-path lookup hit and the ON CONFLICT race leg of parallel
     * retries ([MessageInsertResult.Duplicate]) — i.e. every `200`
     * idempotent acknowledgement; a fresh `201` record is not a dedup
     * hit. The share of deduplications is this counter over all acked
     * sends of [ACK_SECONDS].
     */
    private fun dedupTotal(): Counter =
        Counter
            .builder(DEDUP_TOTAL)
            .description(
                "Send-path dedup hits: retries converged to the stored record (200), " +
                    "both the fast-path and the ON CONFLICT race leg (SC-008)",
            ).register(meterRegistry)

    /**
     * T028 (research.md 004 §11): `webchat_message_ack_seconds` times the
     * send path from the POST arrival (the entry of [send]) to the durable
     * `201`/`200` acknowledgement (SC-001) — only acked sends record a
     * sample; a refused send (400/403/404/409/429) abandons its sample and
     * stays unobserved here.
     */
    private fun ackTimer(outcome: String): Timer =
        Timer
            .builder(ACK_SECONDS)
            .description("Send-path acknowledgement latency: POST arrival to the durable 201/200 outcome (SC-001)")
            .tag(TAG_OUTCOME, outcome)
            .register(meterRegistry)

    private companion object {
        /** research.md 004 §11 observability contract name. */
        const val ACK_SECONDS = "webchat_message_ack_seconds"

        const val TAG_OUTCOME = "outcome"

        /** The two acked outcomes: the `201` fresh record and the `200` FR-004 retry. */
        const val OUTCOME_CREATED = "created"
        const val OUTCOME_EXISTING = "existing"

        /** SC-008 (T037, research.md 004 §11): dedup hits of the send path. */
        const val DEDUP_TOTAL = "webchat_message_dedup_total"
    }
}
