package webchat.backend.conferences.domain.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.SimpleTransactionStatus
import webchat.backend.auth.domain.port.Clock
import webchat.backend.chats.domain.model.Message
import webchat.backend.chats.domain.model.MessageKind
import webchat.backend.chats.domain.model.MessageText
import webchat.backend.chats.domain.port.SystemMessagePort
import webchat.backend.conferences.api.dto.ConferenceMode
import webchat.backend.conferences.domain.model.Conference
import webchat.backend.conferences.domain.model.ConferenceParticipant
import webchat.backend.conferences.domain.model.ParticipantRole
import webchat.backend.conferences.domain.port.ChatInfo
import webchat.backend.conferences.domain.port.ChatMembershipChecker
import webchat.backend.conferences.domain.port.ConferenceCreateResult
import webchat.backend.conferences.domain.port.ConferenceMediaUnavailableException
import webchat.backend.conferences.domain.port.ConferenceRepository
import webchat.backend.conferences.domain.port.LiveKitAdminClient
import webchat.backend.conferences.domain.port.ParticipantRepository
import webchat.backend.config.ConferencesProperties
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * T017 unit regression (tasks.md US1): the №44 create pipeline pinned
 * against the four contract refusals and the research.md §7 ordering —
 * port fakes only, no Spring context (the repository IT set T010 and
 * the story IT T021 hold the SQL and the end-to-end legs):
 *
 *  * **the happy path** — one transaction carries the `created` row,
 *    the creator's `role='moderator'` principal (FR-008) and the
 *    `conference_invite` widget append (FR-002, payload
 *    `{type, conferenceId, mode}` of data-model §Сущность 4), and the
 *    eager `CreateRoom(c:{id}, 60s, 60s, maxParticipants)` fires
 *    strictly AFTER the repository legs (post-commit,
 *    livekit-integration.md §2);
 *  * **the FR-001 idempotence edge** — an `Existing` resolution
 *    answers the competing «double press» with NO principal row, NO
 *    second message and NO room call (invariant 1);
 *  * **the refusal order** — `404 chat_not_found` → `403
 *    conference_forbidden` → `409 conference_e2ee_unsupported` → the
 *    `ListRooms` pre-flight; every gate lands with ZERO writes and
 *    (for the first three) without a media-plane round-trip;
 *  * **the 503 edge** — a failing probe propagates
 *    [ConferenceMediaUnavailableException] BEFORE the repository is
 *    touched (no row, no message — research §7);
 *  * **the orphan rule** — a room failure AFTER the commit must NOT
 *    fail the durable create (the answer stays the winning `201`; the
 *    sweeper T056 re-creates the room, SC-007).
 *
 * Arguments of the mocked Kotlin ports are recorded through stub
 * answers (not ArgumentCaptor) — the matcher factories of plain
 * Mockito return null/platform values the compiler refuses on NON-NULL
 * Kotlin parameters; the two `*NotNull` wrappers below keep the
 * unavoidable `any`/`eq` stubbings legal.
 */
@Suppress("TooManyFunctions") // one leg per №44 rule of the task table
class ConferenceServiceTest {
    private val conferenceRepository = Mockito.mock(ConferenceRepository::class.java)
    private val participantRepository = Mockito.mock(ParticipantRepository::class.java)
    private val membership = Mockito.mock(ChatMembershipChecker::class.java)
    private val liveKit = Mockito.mock(LiveKitAdminClient::class.java)
    private val systemMessages = Mockito.mock(SystemMessagePort::class.java)
    private val transactionManager = Mockito.mock(PlatformTransactionManager::class.java)

    private val recordedParticipants = mutableListOf<ConferenceParticipant>()
    private val recordedWidgets = mutableListOf<Triple<UUID, String, ObjectNode>>()

    private val service =
        ConferenceService(
            conferenceRepository = conferenceRepository,
            participantRepository = participantRepository,
            membership = membership,
            liveKit = liveKit,
            systemMessages = systemMessages,
            properties = TEST_PROPERTIES,
            objectMapper = ObjectMapper(),
            clock = Clock { NOW },
            transactionManager = transactionManager,
        )

    @BeforeEach
    fun stubTransactionAndRecorders() {
        Mockito
            .`when`(transactionManager.getTransaction(ArgumentMatchers.any()))
            .thenReturn(SimpleTransactionStatus())
        Mockito
            .`when`(participantRepository.insert(anyNotNull()))
            .thenAnswer { invocation ->
                invocation.getArgument<ConferenceParticipant>(0).also(recordedParticipants::add)
            }
        Mockito
            .`when`(systemMessages.append(eqNotNull(CHAT_ID), anyNotNull(), anyNotNull()))
            .thenAnswer { invocation ->
                val payload = invocation.getArgument<ObjectNode>(2)
                recordedWidgets +=
                    Triple(invocation.getArgument(0), invocation.getArgument(1), payload)
                // the port answers the stored record; the tests read the
                // recorded arguments only — the shape mirrors kind='system'
                Message(
                    id = UUID.randomUUID(),
                    chatId = CHAT_ID,
                    senderId = null,
                    text = MessageText.normalize("widget"),
                    seq = 1,
                    createdAt = NOW,
                    kind = MessageKind.SYSTEM,
                    payload = payload,
                )
            }
    }

    @Test
    fun `create writes row, moderator principal and invite widget, then the room`() {
        stubChat(e2ee = false)
        stubWinningCreate()

        val result = service.create(CALLER_ID, CHAT_ID, ConferenceMode.VIDEO)

        assertThat(result).isInstanceOf(ConferenceCreateResult.Created::class.java)
        val conference = (result as ConferenceCreateResult.Created).conference
        assertThat(conference.chatId).isEqualTo(CHAT_ID)
        assertThat(conference.createdBy).isEqualTo(CALLER_ID)
        assertThat(conference.maxParticipants)
            .overridingErrorMessage("the room quota snapshot comes from conferences.max-participants (FR-015)")
            .isEqualTo(TEST_PROPERTIES.maxParticipants)

        assertThat(recordedParticipants).hasSize(1)
        val participant = recordedParticipants.single()
        assertThat(participant.conferenceId).isEqualTo(conference.id)
        assertThat(participant.userId).isEqualTo(CALLER_ID)
        assertThat(participant.identity.value).isEqualTo("u:$CALLER_ID")
        assertThat(participant.role)
            .overridingErrorMessage("the creator is the moderator by default (FR-008)")
            .isEqualTo(ParticipantRole.MODERATOR)

        assertThat(recordedWidgets).hasSize(1)
        val (widgetChat, _, payload) = recordedWidgets.single()
        assertThat(widgetChat).isEqualTo(CHAT_ID)
        assertThat(payload.get("type").asText()).isEqualTo("conference_invite")
        assertThat(payload.get("conferenceId").asText()).isEqualTo(conference.id.toString())
        assertThat(payload.get("mode").asText()).isEqualTo("video")

        // post-commit ordering: the eager room follows the durable row (research §7)
        val order = Mockito.inOrder(conferenceRepository, participantRepository, systemMessages, liveKit)
        order.verify(conferenceRepository).create(anyNotNull())
        order.verify(participantRepository).insert(anyNotNull())
        order.verify(systemMessages).append(eqNotNull(CHAT_ID), anyNotNull(), anyNotNull())
        order
            .verify(liveKit)
            .createRoom(conference.roomName, Duration.ofSeconds(60), Duration.ofSeconds(60), 16)
    }

    @Test
    fun `audio mode rides the widget payload and caption`() {
        stubChat(e2ee = false)
        stubWinningCreate()

        service.create(CALLER_ID, CHAT_ID, ConferenceMode.AUDIO)

        val (_, caption, payload) = recordedWidgets.single()
        assertThat(payload.get("mode").asText()).isEqualTo("audio")
        assertThat(caption).isEqualTo("Аудиоконференция")
    }

    @Test
    fun `double press returns the existing conference without a second message or room`() {
        stubChat(e2ee = false)
        val existing =
            Conference.create(UUID.randomUUID(), CHAT_ID, CALLER_ID, TEST_PROPERTIES.maxParticipants, NOW)
        Mockito
            .`when`(conferenceRepository.create(anyNotNull()))
            .thenReturn(ConferenceCreateResult.Existing(existing))

        val result = service.create(CALLER_ID, CHAT_ID, ConferenceMode.VIDEO)

        assertThat(result).isEqualTo(ConferenceCreateResult.Existing(existing))
        assertThat(recordedParticipants).isEmpty()
        assertThat(recordedWidgets).isEmpty()
        Mockito.verify(liveKit).listRoomNames()
        Mockito
            .verify(liveKit, Mockito.never())
            .createRoom(ArgumentMatchers.anyString(), anyNotNull(), anyNotNull(), ArgumentMatchers.anyInt())
    }

    @Test
    fun `unknown chat answers chat_not_found before any probe or write`() {
        Mockito.`when`(membership.findChatInfo(CHAT_ID)).thenReturn(null)

        assertThatThrownBy { service.create(CALLER_ID, CHAT_ID, ConferenceMode.VIDEO) }
            .isInstanceOf(ConferenceChatNotFoundException::class.java)

        Mockito.verifyNoInteractions(liveKit, conferenceRepository, participantRepository, systemMessages)
    }

    @Test
    fun `non-member answers conference_forbidden without a media-plane round-trip`() {
        stubChat(e2ee = false)
        Mockito.`when`(membership.isMember(CHAT_ID, CALLER_ID)).thenReturn(false)

        assertThatThrownBy { service.create(CALLER_ID, CHAT_ID, ConferenceMode.VIDEO) }
            .isInstanceOf(ConferenceForbiddenException::class.java)

        Mockito.verifyNoInteractions(liveKit, conferenceRepository)
        assertThat(recordedParticipants).isEmpty()
        assertThat(recordedWidgets).isEmpty()
    }

    @Test
    fun `e2ee chat answers conference_e2ee_unsupported before any probe or write`() {
        stubChat(e2ee = true)

        assertThatThrownBy { service.create(CALLER_ID, CHAT_ID, ConferenceMode.VIDEO) }
            .isInstanceOf(ConferenceE2eeUnsupportedException::class.java)

        Mockito.verifyNoInteractions(liveKit, conferenceRepository)
        assertThat(recordedParticipants).isEmpty()
        assertThat(recordedWidgets).isEmpty()
    }

    @Test
    fun `a failing pre-flight probe propagates with nothing written`() {
        stubChat(e2ee = false)
        Mockito
            .`when`(liveKit.listRoomNames())
            .thenThrow(ConferenceMediaUnavailableException(IllegalStateException("plane down")))

        assertThatThrownBy { service.create(CALLER_ID, CHAT_ID, ConferenceMode.VIDEO) }
            .isInstanceOf(ConferenceMediaUnavailableException::class.java)

        Mockito.verifyNoInteractions(conferenceRepository)
        assertThat(recordedParticipants).isEmpty()
        assertThat(recordedWidgets).isEmpty()
    }

    @Test
    fun `a room failure after the commit does not fail the durable create`() {
        stubChat(e2ee = false)
        stubWinningCreate()
        Mockito
            .`when`(
                liveKit.createRoom(anyNotNull(), anyNotNull(), anyNotNull(), ArgumentMatchers.anyInt()),
            ).thenThrow(ConferenceMediaUnavailableException(IllegalStateException("plane died after commit")))

        val result = service.create(CALLER_ID, CHAT_ID, ConferenceMode.VIDEO)

        assertThat(result).isInstanceOf(ConferenceCreateResult.Created::class.java)
    }

    @Test
    fun `a widget append failure rolls the whole create back`() {
        stubChat(e2ee = false)
        stubWinningCreate()
        Mockito
            .`when`(systemMessages.append(eqNotNull(CHAT_ID), anyNotNull(), anyNotNull()))
            .thenThrow(IllegalStateException("history write failed"))

        assertThatThrownBy { service.create(CALLER_ID, CHAT_ID, ConferenceMode.VIDEO) }
            .isInstanceOf(IllegalStateException::class.java)

        Mockito
            .verify(liveKit, Mockito.never())
            .createRoom(ArgumentMatchers.anyString(), anyNotNull(), anyNotNull(), ArgumentMatchers.anyInt())
    }

    private fun stubChat(e2ee: Boolean) {
        Mockito.`when`(membership.findChatInfo(CHAT_ID)).thenReturn(ChatInfo(id = CHAT_ID, e2ee = e2ee))
        Mockito.`when`(membership.isMember(CHAT_ID, CALLER_ID)).thenReturn(true)
    }

    /** The repository leg of the winning INSERT — the row the service just composed. */
    private fun stubWinningCreate() {
        Mockito
            .`when`(conferenceRepository.create(anyNotNull()))
            .thenAnswer { invocation -> ConferenceCreateResult.Created(invocation.getArgument<Conference>(0)) }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-10-16T09:00:00Z")

        val CHAT_ID: UUID = UUID.fromString("00000000-0000-4000-8000-00000000c0a7")

        val CALLER_ID: UUID = UUID.fromString("00000000-0000-4000-8000-00000000a11c")

        val TEST_PROPERTIES =
            ConferencesProperties(
                maxParticipants = 16,
                tokenTtl = Duration.ofSeconds(900),
                guestInviteTtl = Duration.ofHours(24),
                emptyRoomTimeout = Duration.ofSeconds(60),
                departureTimeout = Duration.ofSeconds(60),
                maxDuration = Duration.ofHours(4),
                warningBeforeEnd = Duration.ofMinutes(5),
                reconnectWindow = Duration.ofSeconds(120),
                guestNameMaxLength = 50,
                sweepInterval = Duration.ofSeconds(60),
                roomLostGrace = Duration.ofSeconds(120),
                livekit = ConferencesProperties.LiveKit("http://localhost:7880", "devkey", "devsecret"),
                rateLimit = ConferencesProperties.RateLimit("30/1m", "10/1m"),
            )
    }
}

/**
 * The Kotlin null-safety seam of Mockito on the conferences Kotlin
 * ports: the matcher factories return null/platform values the
 * compiler refuses on NON-NULL Kotlin parameters — Kotlin-typed
 * wrappers keep the stubbing expressions legal (the mockito-kotlin
 * trick, inlined for this file; the unbounded `T` keeps the casts
 * unchecked at runtime).
 */
@Suppress("UNCHECKED_CAST")
private fun <T> anyNotNull(): T = ArgumentMatchers.any() as T

/** [anyNotNull] for [ArgumentMatchers.eq]. */
@Suppress("UNCHECKED_CAST")
private fun <T> eqNotNull(value: T): T = ArgumentMatchers.eq(value) as T
