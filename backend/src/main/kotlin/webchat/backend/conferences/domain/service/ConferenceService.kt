package webchat.backend.conferences.domain.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import webchat.backend.auth.domain.port.Clock
import webchat.backend.chats.domain.port.SystemMessagePort
import webchat.backend.conferences.api.dto.ConferenceMode
import webchat.backend.conferences.domain.model.Conference
import webchat.backend.conferences.domain.model.ConferenceParticipant
import webchat.backend.conferences.domain.model.ParticipantRole
import webchat.backend.conferences.domain.port.ChatMembershipChecker
import webchat.backend.conferences.domain.port.ConferenceCreateResult
import webchat.backend.conferences.domain.port.ConferenceRepository
import webchat.backend.conferences.domain.port.LiveKitAdminClient
import webchat.backend.conferences.domain.port.ParticipantRepository
import webchat.backend.config.ConferencesProperties
import java.util.UUID

/**
 * T017 (009, US1) — the №44 create of a conference:
 * `POST /api/v1/chats/{chatId}/conferences`.
 *
 * The operation is the four-gate pipeline of research.md §7 /
 * livekit-integration.md §2, in the contract's refusal order
 * (api-contract.md 009 №44):
 *
 * 1. **chat resolution** — [ChatMembershipChecker.findChatInfo]:
 * `null` → [ConferenceChatNotFoundException] (`404 chat_not_found`);
 * 2. **membership gate** (FR-003, SC-002) — the every-operation ACTIVE
 *    row check, a stranger and a №32/№33-former member answer the same
 *    [ConferenceForbiddenException] (`403 conference_forbidden`);
 * 3. **E2EE boundary** (FR-001, the v1 line with 010) — the V18
 *    `chats.e2ee` flag → [ConferenceE2eeUnsupportedException] (`409
 *    conference_e2ee_unsupported`);
 * 4. **media-plane pre-flight** — the `ListRooms` probe BEFORE the
 *    transaction: an unreachable plane surfaces as
 *    [ConferenceMediaUnavailableException] (`503
 *    conference_media_unavailable`) with NOTHING written to the DB
 *    (edge «звонок временно недоступен»; the probe/room race stays
 *    with the sweeper, research §7).
 *
 * Then ONE transaction ([TransactionTemplate]): the `created` row
 * resolves the `ux_conferences_chat_active` idempotence — the winner
 * additionally inserts the creator's `role='moderator'` principal row
 * (FR-008) and posts the system invite widget through the chats
 * [SystemMessagePort] (FR-002; the INSERT, the `seq` allocation and
 * the post-commit `message.created` fan-out ride the chats 004/006
 * discipline verbatim), the «double press» loser gets the existing row
 * back with NO second row, NO message and NO room call (invariant 1).
 * The eager `CreateRoom` (`c:{id}`, `empty_timeout`/
 * `departure_timeout`/`max_participants` of [ConferencesProperties])
 * fires strictly POST-COMMIT: the row is durable first, so a crash
 * between commit and room leaves an orphan the lifecycle sweeper
 * (T056, SC-007) re-creates — never a room without a row.
 */
@Service
@Suppress("LongParameterList") // the №44 collaborators, one port per leg (DIP, plan.md VIII)
class ConferenceService(
    private val conferenceRepository: ConferenceRepository,
    private val participantRepository: ParticipantRepository,
    private val membership: ChatMembershipChecker,
    private val liveKit: LiveKitAdminClient,
    private val systemMessages: SystemMessagePort,
    private val properties: ConferencesProperties,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
    transactionManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(ConferenceService::class.java)

    /**
     * The ONE transaction of the create: row + creator row + invite
     * widget commit or roll back TOGETHER (the repository `create`,
     * the principal `insert` and the [SystemMessagePort.append] all
     * join this ambient template — the JdbcTemplate convention without
     * JPA). Opened only AFTER the four gates passed, so every contract
     * refusal lands with zero writes.
     */
    private val createTransaction = TransactionTemplate(transactionManager)

    /**
     * №44 (api-contract.md 009 §2): resolves [ConferenceCreateResult.Created]
     * (the row was inserted — the caller answers `201`) or
     * [ConferenceCreateResult.Existing] (a non-ended conference already
     * runs in the chat — `200`, FR-001 idempotence). [mode] shapes ONLY
     * the invite widget payload/caption (the pre-join defaults carrier);
     * it never narrows grants and is never stored on the row.
     *
     * `ThrowsCount` suppressed: the three throw legs ARE the №44
     * refusal ladder of api-contract.md 009 §2, in the contract order.
     */
    @Suppress("ThrowsCount")
    fun create(
        callerId: UUID,
        chatId: UUID,
        mode: ConferenceMode,
    ): ConferenceCreateResult {
        val chat =
            membership.findChatInfo(chatId)
                ?: throw ConferenceChatNotFoundException()
        if (!membership.isMember(chatId, callerId)) {
            throw ConferenceForbiddenException()
        }
        if (chat.e2ee) {
            throw ConferenceE2eeUnsupportedException()
        }
        // The pre-flight probe: ANY successful ListRooms proves the
        // plane; failure propagates as the 503 edge BEFORE any write
        // (research §7). Runs after the gates so a non-member never
        // spends a media-plane round-trip.
        liveKit.listRoomNames()

        val conferenceId = UUID.randomUUID()
        val now = clock.now()
        val outcome =
            createTransaction.execute {
                val result =
                    conferenceRepository.create(
                        Conference.create(
                            id = conferenceId,
                            chatId = chatId,
                            createdBy = callerId,
                            maxParticipants = properties.maxParticipants,
                            at = now,
                        ),
                    )
                if (result is ConferenceCreateResult.Created) {
                    // The creator's principal row: moderator by default
                    // (FR-008/Q21=A — equal moderators, no «владелец»).
                    participantRepository.insert(
                        ConferenceParticipant.member(
                            id = UUID.randomUUID(),
                            conferenceId = conferenceId,
                            userId = callerId,
                            firstSeenAt = now,
                            role = ParticipantRole.MODERATOR,
                        ),
                    )
                    // FR-002: the invite card lands in the chat history
                    // inside THIS transaction; the port registers the
                    // post-commit fan-out on its own.
                    systemMessages.append(chatId, widgetCaption(mode), invitePayload(conferenceId, mode))
                }
                result
            } ?: error("the create transaction must return its outcome")

        if (outcome is ConferenceCreateResult.Created) {
            createRoomAfterCommit(outcome.conference)
        }
        return outcome
    }

    /**
     * The eager room of №44 — strictly post-commit (the transaction
     * template has returned, the row is durable):
     * `CreateRoom(c:{id}, empty_timeout, departure_timeout,
     * max_participants)` of livekit-integration.md §2/Q19. A failure
     * here MUST NOT fail the already-committed operation (the contract
     * 503 means «nothing was written»): the `created` row without a
     * room is the documented orphan the sweeper self-heals by
     * re-creating the room (T056, research §7, SC-007) — the answer
     * stays the winning `201`, the warn log carries ids only
     * (constitution V).
     *
     * `TooGenericExceptionCaught` suppressed: the committed row
     * outranks the room call; the sweeper (T056) re-creates the orphan.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun createRoomAfterCommit(conference: Conference) {
        try {
            liveKit.createRoom(
                name = conference.roomName,
                emptyTimeout = properties.emptyRoomTimeout,
                departureTimeout = properties.departureTimeout,
                maxParticipants = conference.maxParticipants,
            )
        } catch (failure: Exception) {
            log.warn(
                "eager CreateRoom for conference <{}> failed after the create transaction committed; " +
                    "the sweeper re-creates the orphaned room (research §7, SC-007): {}",
                conference.id,
                failure.message,
            )
        }
    }

    /** The widget heading of the invite card — the DB `text` placeholder of data-model 009 §Сущность 4. */
    private fun widgetCaption(mode: ConferenceMode): String =
        when (mode) {
            ConferenceMode.VIDEO -> "Видеоконференция"
            ConferenceMode.AUDIO -> "Аудиоконференция"
        }

    /**
     * The invite widget payload of data-model 009 §Сущность 4:
     * `{"type":"conference_invite","conferenceId":"<uuid>","mode":"video|audio"}`
     * — the «Подключиться» target and the pre-join defaults carrier;
     * unknown `payload.type` renders as a neutral placeholder on
     * clients (OCP, constitution IV). Tiny by construction, well under
     * the [webchat.backend.chats.domain.model.Message.MAX_PAYLOAD_BYTES]
     * cap the append port enforces.
     */
    private fun invitePayload(
        conferenceId: UUID,
        mode: ConferenceMode,
    ): ObjectNode =
        objectMapper
            .createObjectNode()
            .put(PAYLOAD_TYPE, CONFERENCE_INVITE_TYPE)
            .put(PAYLOAD_CONFERENCE_ID, conferenceId.toString())
            .put(PAYLOAD_MODE, mode.name.lowercase())

    private companion object {
        const val PAYLOAD_TYPE = "type"

        const val PAYLOAD_CONFERENCE_ID = "conferenceId"

        const val PAYLOAD_MODE = "mode"

        const val CONFERENCE_INVITE_TYPE = "conference_invite"
    }
}
