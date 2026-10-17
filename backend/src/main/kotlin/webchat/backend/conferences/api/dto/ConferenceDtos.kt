package webchat.backend.conferences.api.dto

import com.fasterxml.jackson.annotation.JsonInclude
import webchat.backend.conferences.domain.model.ConferenceEndReason
import webchat.backend.conferences.domain.model.ConferenceStatus
import webchat.backend.conferences.domain.model.ParticipantRestrictions
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

/**
 * 400 (api-contract.md 009 №49/№50/№51): the PATH `{identity}` segment
 * is blank — `errors: {identity: [malformed_request]}`. Nothing of the
 * submitted value is echoed (constitution V); rendered by
 * [webchat.backend.conferences.api.ConferencesExceptionHandler] as a
 * typed problem.
 */
class InvalidParticipantIdentityException : RuntimeException("identity must not be blank")

/**
 * 400 (api-contract.md 009 №49): the body is not the full
 * SET-state of four booleans — `errors: {restrictions:
 * [malformed_request]}` (a missing flag makes a partial update, which
 * the SET semantics forbid — api-contract.md §2 №49). The same
 * raw-nullable-then-validate discipline as
 * [CreateConferenceRequest].
 */
class InvalidParticipantRestrictionsException :
    RuntimeException("restrictions must be the full SET of the four booleans")

/**
 * 400 (api-contract.md 009 №50): the submitted `role` is absent or
 * outside `moderator|participant` — `errors: {role: [invalid_role]}`.
 * The submitted value is never echoed (constitution V).
 */
class InvalidParticipantRoleException : RuntimeException("role must be one of: moderator, participant")

/**
 * Contract №52 success body — `GuestInviteView` (api-contract.md §4):
 * the freshly minted guest link — the signed nonce inside the PUBLIC
 * SPA route [inviteUrl] (`{FRONTEND}/join/{inviteToken}`, T050) and
 * the [expiresAt] horizon (= `createdAt + conferences.guest-invite-ttl`,
 * 24 h default Q19=A — the nonce `exp` and the status row share it by
 * construction, data-model 009 §Сущность 3). ONE active link per
 * conference: every №52 answer has already revoked its predecessor in
 * the same transaction (Q17=A) and disconnected its connected guests
 * (Q13=B). Never posted to the chat (FR-009) — delivery rides the
 * moderator's own channel.
 */
data class GuestInviteView(
    val inviteUrl: String,
    val expiresAt: Instant,
)

/**
 * Contract №49 request body — `UpdateParticipantRestrictionsRequest`
 * (api-contract.md §4, openapi.yaml 0.11.0): the FULL SET-state of the
 * four moderation flags, all four required (a partial update is
 * impossible; a reset is an explicit `false`, api-contract.md §2 №49).
 *
 * The flags arrive as nullable booleans on purpose (the
 * [CreateConferenceRequest] convention): a missing flag is rejected
 * HERE as the contract 400 `errors: {restrictions:
 * [malformed_request]}` instead of dying in deserialization — the
 * reply stays a typed problem. The moderator/ended/target legs are
 * caller-dependent and stay with the T039 controller +
 * [webchat.backend.conferences.domain.service.ModerationService].
 */
data class UpdateParticipantRestrictionsRequest(
    val micRestricted: Boolean? = null,
    val cameraRestricted: Boolean? = null,
    val screenRestricted: Boolean? = null,
    val speechRestricted: Boolean? = null,
) {
    /** №49 shape check: the complete [ParticipantRestrictions], or `400 malformed_request`. */
    fun validatedRestrictions(): ParticipantRestrictions =
        ParticipantRestrictions(
            micRestricted = requiredFlag(micRestricted),
            cameraRestricted = requiredFlag(cameraRestricted),
            screenRestricted = requiredFlag(screenRestricted),
            speechRestricted = requiredFlag(speechRestricted),
        )

    private fun requiredFlag(flag: Boolean?): Boolean = flag ?: throw InvalidParticipantRestrictionsException()
}

/**
 * Contract №50 request body — `UpdateParticipantRoleRequest`
 * (api-contract.md §4): exactly `{role: "moderator" | "participant"}`
 * — the elevation/demotion pair of the EQUAL moderators (Q21=A; no
 * «владелец» role exists, the creator just STARTS as a moderator).
 * The raw-string-then-validate discipline of [CreateConferenceRequest]:
 * an absent/unknown label is the contract 400 `errors: {role:
 * [invalid_role]}`.
 */
data class UpdateParticipantRoleRequest(
    val role: String? = null,
) {
    /** №50 shape check: the [ParticipantRole] of the label, or `400 invalid_role`. */
    fun validatedRole(): ParticipantRole =
        when (role) {
            MODERATOR_ROLE -> ParticipantRole.MODERATOR
            PARTICIPANT_ROLE -> ParticipantRole.PARTICIPANT
            else -> throw InvalidParticipantRoleException()
        }

    private companion object {
        private const val MODERATOR_ROLE = "moderator"
        private const val PARTICIPANT_ROLE = "participant"
    }
}
