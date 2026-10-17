package webchat.backend.conferences.api.dto

import com.fasterxml.jackson.annotation.JsonInclude
import webchat.backend.conferences.domain.model.ConferenceEndReason
import webchat.backend.conferences.domain.model.ConferenceStatus
import webchat.backend.conferences.domain.model.ParticipantRole
import java.time.Instant
import java.util.UUID

/**
 * The №44 pre-join defaults mode (api-contract.md §4, the
 * `CreateConferenceRequest.mode`/`ConferenceView.mode` enum `video|audio`,
 * FR-001): `video` — camera+microphone, `audio` — microphone only (the
 * camera toggle stays available — soft, Q3=A). The mode shapes ONLY the
 * client's start state: it rides the invite widget payload
 * `{"type":"conference_invite","mode":…}` (data-model 009 §Сущность 4,
 * T016) and every `ConferenceView` — it never narrows sources or grants
 * (unlike [webchat.backend.conferences.domain.model.ParticipantRestrictions]).
 */
enum class ConferenceMode {
    VIDEO,
    AUDIO,
}

/** The lowercase contract label of a [ConferenceMode] (schema enum video|audio). */
fun conferenceModeLabel(mode: ConferenceMode): String = mode.name.lowercase()

/** The lowercase contract label of a [ConferenceStatus] (schema enum created|active|ended). */
fun conferenceStatusLabel(status: ConferenceStatus): String = status.name.lowercase()

/** The lowercase contract label of a [ConferenceEndReason] (schema enum last_left|…|chat_deleted). */
fun conferenceEndReasonLabel(reason: ConferenceEndReason): String = reason.name.lowercase()

/** The lowercase contract label of a [ParticipantRole] (schema enum moderator|participant). */
fun participantRoleLabel(role: ParticipantRole): String = role.name.lowercase()

/**
 * №44 `400 errors: {mode: [invalid_mode]}` (openapi.yaml conferences
 * №44): the submitted `mode` is absent or outside `video|audio`. The
 * submitted value is never echoed (constitution V); rendered by the
 * T019 `ConferencesExceptionHandler` as a typed problem.
 */
class InvalidConferenceModeException : RuntimeException("mode must be one of: video, audio")

/**
 * Contract №44 request body — `CreateConferenceRequest` (api-contract.md
 * §4, openapi.yaml 0.11.0): exactly `{mode: "video" | "audio"}`.
 *
 * The value arrives as a raw string on purpose (the
 * [webchat.backend.chats.api.dto.EnsureChatRequest] convention): an
 * absent or unknown label is rejected HERE as the contract 400
 * `errors: {mode: [invalid_mode]}` (problem+json), instead of dying in
 * deserialization — the reply stays a typed problem, never the default
 * error page. The E2EE/membership/media-plane rules are caller-dependent
 * and stay with `ConferenceService.create` (T017: `403
 * conference_forbidden`, `409 conference_e2ee_unsupported`, `503
 * conference_media_unavailable`).
 */
data class CreateConferenceRequest(
    val mode: String? = null,
) {
    /** №44 shape check: [ConferenceMode] of the label, or `400 invalid_mode`. */
    fun validatedMode(): ConferenceMode =
        when (mode) {
            VIDEO_MODE -> ConferenceMode.VIDEO
            AUDIO_MODE -> ConferenceMode.AUDIO
            else -> throw InvalidConferenceModeException()
        }

    private companion object {
        private const val VIDEO_MODE = "video"
        private const val AUDIO_MODE = "audio"
    }
}

/**
 * Contract №44/№45 success body — `ConferenceView` (api-contract.md §4):
 * the conference as seen by a chat member — the lifecycle, the pre-join
 * mode, the connection quota. The response of the eager №44 create
 * (`201`, or `200` of the existing row — the FR-001 idempotency edge)
 * and of №45, INCLUDING `status='ended'` (the stale-link «конференция
 * завершена» answer of US1-6).
 *
 * [startedAt]/[endedAt]/[endReason] are optional (NON_NULL): `startedAt`
 * is absent while `created` (the first `participant_joined` sets it),
 * `endedAt`/[endReason] are absent until finalize (api-contract.md §4).
 * [activeParticipants] is the media-plane connection count at request
 * time — the DB keeps no connection mirror (research §4/§7), so the
 * controller (T019) fills it from `LiveKitAdminClient.ListParticipants`,
 * and [mode] from the invite widget of the chat history (data-model 009
 * §Сущность 4 — the conferences row stores no mode).
 */
data class ConferenceView(
    val id: UUID,
    val chatId: UUID,
    val status: String,
    val mode: String,
    val createdBy: UUID,
    val createdAt: Instant,
    val maxParticipants: Int,
    val activeParticipants: Int,
    @JsonInclude(JsonInclude.Include.NON_NULL) val startedAt: Instant? = null,
    @JsonInclude(JsonInclude.Include.NON_NULL) val endedAt: Instant? = null,
    @JsonInclude(JsonInclude.Include.NON_NULL) val endReason: String? = null,
)

/**
 * Contract №46 success body — `MeView` (api-contract.md §4): the
 * caller's role and the four moderation restrictions of his principal
 * row, for blocking the pre-join/in-call toggles (FR-007). The values
 * are the persisted [webchat.backend.conferences.domain.model.ConferenceParticipant]
 * state re-read at request time — restrictions survive reconnect and
 * re-apply to every re-mint (Q5/Q22=A); the live permission update by
 * `UpdateParticipant` (T036) is the media-plane twin of this snapshot.
 */
data class MeView(
    val role: String,
    val micRestricted: Boolean,
    val cameraRestricted: Boolean,
    val screenRestricted: Boolean,
    val speechRestricted: Boolean,
)

/**
 * Contract №49/№50 success body — `ParticipantView` (api-contract.md
 * §4): a conference principal — the element of the roster panel
 * (T041). [identity] is the opaque LiveKit principal `u:{userId}` /
 * `g:{connId}` — no PII; [name] is the member's username or the
 * guest's entered name; [online] is the media-plane live flag, NEVER a
 * stored state (the DB row survives disconnects by design, Q5/Q9). The
 * №49/№50 answers return the row AFTER the applied change (the SET
 * semantics + the persistent role), so the panel re-renders from one
 * object.
 */
data class ParticipantView(
    val identity: String,
    val name: String,
    val role: String,
    val guest: Boolean,
    val micRestricted: Boolean,
    val cameraRestricted: Boolean,
    val screenRestricted: Boolean,
    val speechRestricted: Boolean,
    val online: Boolean,
)

/**
 * Contract №47/№55 success body — `JoinTicket` (api-contract.md §4):
 * the short-lived admission to the media plane — the LiveKit JWT
 * (HS256, TTL `conferences.token-ttl`, default 900 s), the WS ingress
 * [wsUrl], the full connection [identity] and the display [name]. The
 * token carries NO admin grants — all moderation is server-side
 * (FR-008); grants are computed from the role/restrictions at mint
 * (T018/T046, research §4). An expired mid-call token is renewed by a
 * repeated №47/№55 within the reconnect window (default 120 s):
 * [expiresAt] tells the client when.
 */
data class JoinTicket(
    val token: String,
    val wsUrl: String,
    val identity: String,
    val name: String,
    val expiresAt: Instant,
)
