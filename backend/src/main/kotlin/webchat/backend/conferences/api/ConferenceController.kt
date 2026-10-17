package webchat.backend.conferences.api

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import webchat.backend.conferences.api.dto.ConferenceMode
import webchat.backend.conferences.api.dto.ConferenceView
import webchat.backend.conferences.api.dto.CreateConferenceRequest
import webchat.backend.conferences.api.dto.InvalidParticipantIdentityException
import webchat.backend.conferences.api.dto.JoinTicket
import webchat.backend.conferences.api.dto.MeView
import webchat.backend.conferences.api.dto.ParticipantView
import webchat.backend.conferences.api.dto.UpdateParticipantRestrictionsRequest
import webchat.backend.conferences.api.dto.UpdateParticipantRoleRequest
import webchat.backend.conferences.api.dto.conferenceEndReasonLabel
import webchat.backend.conferences.api.dto.conferenceModeLabel
import webchat.backend.conferences.api.dto.conferenceStatusLabel
import webchat.backend.conferences.api.dto.participantRoleLabel
import webchat.backend.conferences.domain.model.Conference
import webchat.backend.conferences.domain.model.ConferenceIdentity
import webchat.backend.conferences.domain.model.ParticipantRole
import webchat.backend.conferences.domain.port.ChatMembershipChecker
import webchat.backend.conferences.domain.port.ConferenceCreateResult
import webchat.backend.conferences.domain.port.ConferenceModeReader
import webchat.backend.conferences.domain.port.ConferenceRepository
import webchat.backend.conferences.domain.port.LiveKitAdminClient
import webchat.backend.conferences.domain.port.MemberNameReader
import webchat.backend.conferences.domain.port.ParticipantRepository
import webchat.backend.conferences.domain.service.ConferenceEndedException
import webchat.backend.conferences.domain.service.ConferenceForbiddenException
import webchat.backend.conferences.domain.service.ConferenceNotFoundException
import webchat.backend.conferences.domain.service.ConferenceService
import webchat.backend.conferences.domain.service.JoinTokenService
import webchat.backend.conferences.domain.service.ModerationService
import webchat.backend.conferences.domain.service.ModerationUpdate
import webchat.backend.conferences.domain.service.ModeratorRequiredException
import java.util.UUID

/**
 * T019 (009, US1) + T039 (009, US3) — the member surface of the
 * `conferences` tag: №44 `POST /api/v1/chats/{chatId}/conferences`,
 * №45 `GET /api/v1/conferences/{conferenceId}`, №46 `GET …/me`, №47
 * `POST …/join` (T019) and the moderation family №48 `POST …/end`,
 * №49 `PUT …/participants/{identity}/restrictions`, №50 `PUT
 * …/participants/{identity}/role`, №51 `DELETE
 * …/participants/{identity}` (T039) — a thin HTTP adapter in the
 * [webchat.backend.chats.api.ChatController] discipline: the owner id
 * is the token `sub` claim, the raw path segments refuse as the typed
 * 400 `invalid_uuid` problems BEFORE any collaborator is touched, and
 * every business refusal leaves as a typed exception rendered
 * problem+json by [ConferencesExceptionHandler].
 *
 * The division of labor the services were built for (their KDocs pin
 * it): №44 delegates its whole gate ladder to
 * [ConferenceService.create] (T017); №47 resolves the contract ladder
 * HERE — `404 conference_not_found` → the EVERY-JOIN membership gate
 * `403 conference_forbidden` (FR-003/SC-002: the check runs on EVERY
 * mint, BEFORE [JoinTokenService] touches the quota probe or the
 * principal row — a non-member never mints) → `409 conference_ended`
 * on a non-ended row — and hands the service exactly the two
 * mint-time legs (`409 conference_full`, `503
 * conference_media_unavailable`). №45/№46 fold the non-member and the
 * unknown id into the SAME uniform `404 conference_not_found`
 * (api-contract.md §1 — the existence of a conference is never
 * disclosed; only №44/№47 split the explicit 403).
 *
 * The №48–№51 moderation ladder of T039 (api-contract.md §1): the
 * SAME uniform 404 of [resolveForRead] (a stranger never learns the
 * conference exists), then the ROLE gate `403 moderator_required`
 * ([requireModerator]) — the row of the CALLER only: moderators are
 * EQUAL and act on ANY target including each other and the creator
 * (Q21=A — no «владелец» role exists), so the gate deliberately knows
 * nothing about the `{identity}` it is pointed at — and only then the
 * [ModerationService] legs with their own refusals (`409
 * conference_ended` of the №48 repeat, `404 participant_not_found` of
 * the folded target). The one ordering subtlety: №48 checks the
 * ENDED row BEFORE the role gate — the finalize deleted the whole
 * roster (Q18=A), so the gate row is gone exactly when the contract's
 * repeat answer `409 conference_ended` must win over an undecidable
 * 403.
 *
 * The [ConferenceView] projection: `activeParticipants` is the
 * media-plane connection truth of `ListParticipants` (the DB mirrors
 * none of it, research §4/§7) and `mode` comes from the invite widget
 * of the chat history through [ConferenceModeReader] — EXCEPT the
 * fresh `201` of №44, which answers the request's own mode with a
 * zero count: the row was just inserted as the only principal-less
 * conference possible, and a media round-trip there could flip the
 * already-committed create into a 503 the T017 post-commit rule
 * explicitly forbids (a room/plane failure after the commit must not
 * fail the durable create).
 */
@RestController
// the №44–№51 collaborators, one port per leg (DIP, plan.md VIII); one
// endpoint per №44–№51 operation plus the shared view/path helpers —
// T045 extends the same class
@Suppress("LongParameterList", "TooManyFunctions")
class ConferenceController(
    private val conferenceService: ConferenceService,
    private val joinTokenService: JoinTokenService,
    private val moderationService: ModerationService,
    private val conferenceRepository: ConferenceRepository,
    private val participantRepository: ParticipantRepository,
    private val membership: ChatMembershipChecker,
    private val inviteModes: ConferenceModeReader,
    private val memberNames: MemberNameReader,
    private val liveKit: LiveKitAdminClient,
) {
    /**
     * №44 (api-contract.md 009 §2): `201 ConferenceView` for the winner
     * of the `ux_conferences_chat_active` race (the system invite
     * message rode the same transaction, FR-002), `200` of the EXISTING
     * conference for the competing «double press» — no second message
     * (FR-001 edge), and the view answers the mode OF THE CONFERENCE
     * (the widget's), not the submitted one.
     */
    @PostMapping("/api/v1/chats/{chatId}/conferences")
    fun create(
        @PathVariable chatId: String,
        @RequestBody request: CreateConferenceRequest,
        @AuthenticationPrincipal accessToken: Jwt,
    ): ResponseEntity<ConferenceView> {
        val parsedChatId = parseChatId(chatId)
        val mode = request.validatedMode()
        return when (val result = conferenceService.create(callerId(accessToken), parsedChatId, mode)) {
            is ConferenceCreateResult.Created ->
                ResponseEntity.status(HttpStatus.CREATED).body(viewOf(result.conference, mode, activeParticipants = 0))
            is ConferenceCreateResult.Existing -> ResponseEntity.ok(view(result.conference))
        }
    }

    /**
     * №45 (api-contract.md 009 §2): the member read INCLUDING
     * `status='ended'` — the stale-link «конференция завершена» answer
     * of US1-6 (the finalized row survives, Q18=A). An ended conference
     * answers its zero count WITHOUT a media round-trip: the room is
     * gone by definition of finalize (SC-007).
     */
    @GetMapping("/api/v1/conferences/{conferenceId}")
    fun getConference(
        @PathVariable conferenceId: String,
        @AuthenticationPrincipal accessToken: Jwt,
    ): ConferenceView = view(resolveForRead(parseConferenceId(conferenceId), callerId(accessToken)))

    /**
     * №46 (api-contract.md 009 §2): the caller's role and the four
     * moderation restrictions of his principal row — the pre-join
     * toggle gating source (FR-007). A member who has not joined YET
     * gets the DEFAULT `MeView` (`participant`, nothing restricted): a
     * read never creates rows, and his first №47 will insert exactly
     * that row (the [ParticipantRepository.findMember] contract).
     */
    @GetMapping("/api/v1/conferences/{conferenceId}/me")
    fun me(
        @PathVariable conferenceId: String,
        @AuthenticationPrincipal accessToken: Jwt,
    ): MeView {
        val callerId = callerId(accessToken)
        val conference = resolveForRead(parseConferenceId(conferenceId), callerId)
        val principal = participantRepository.findMember(conference.id, callerId)
        val restrictions = principal?.restrictions
        return MeView(
            role = participantRoleLabel(principal?.role ?: ParticipantRole.PARTICIPANT),
            micRestricted = restrictions?.micRestricted ?: false,
            cameraRestricted = restrictions?.cameraRestricted ?: false,
            screenRestricted = restrictions?.screenRestricted ?: false,
            speechRestricted = restrictions?.speechRestricted ?: false,
        )
    }

    /**
     * №47 (api-contract.md 009 §2): the [JoinTicket] mint of a chat
     * member. The ladder runs HERE, in the contract's refusal order —
     * the row (`404 conference_not_found`), the EVERY-JOIN membership
     * gate (FR-003/SC-002: `403 conference_forbidden` BEFORE any quota
     * probe, principal row or token — a stranger, a №32-kicked and a
     * №33-left former member answer identically and never mint), the
     * `409 conference_ended` of an old link — and the service owns the
     * mint-time legs (`409 conference_full` of the FR-015 quota,
     * `503 conference_media_unavailable`).
     *
     * `ThrowsCount` suppressed: the three throw statements ARE the №47
     * refusal ladder of api-contract.md 009 §2, in the contract order.
     */
    @Suppress("ThrowsCount")
    @PostMapping("/api/v1/conferences/{conferenceId}/join")
    fun join(
        @PathVariable conferenceId: String,
        @AuthenticationPrincipal accessToken: Jwt,
    ): JoinTicket {
        val callerId = callerId(accessToken)
        val conference =
            conferenceRepository.findById(parseConferenceId(conferenceId))
                ?: throw ConferenceNotFoundException()
        if (!membership.isMember(conference.chatId, callerId)) {
            throw ConferenceForbiddenException()
        }
        if (conference.isEnded) {
            throw ConferenceEndedException()
        }
        return joinTokenService.mintMember(callerId, conference)
    }

    /**
     * №48 (api-contract.md 009 §2, T039): the moderator's end of the
     * WHOLE conference — `204`, everyone disconnects through the
     * `DeleteRoom` of [ModerationService.end]. The ladder: the uniform
     * `404 conference_not_found` of [resolveForRead] → `409
     * conference_ended` of an ALREADY-ended row (deliberately BEFORE
     * the role gate: the finalize deleted the whole roster, Q18=A, so
     * the gate row is gone exactly when the contract's repeat answer
     * must win over an undecidable 403) → `403 moderator_required` of
     * [requireModerator] → the service's own ended re-check as the
     * concurrent-end race backstop.
     *
     * `ThrowsCount` suppressed: the throw statements ARE the №48
     * refusal ladder of api-contract.md 009 §2, in the contract order.
     */
    @Suppress("ThrowsCount")
    @PostMapping("/api/v1/conferences/{conferenceId}/end")
    fun end(
        @PathVariable conferenceId: String,
        @AuthenticationPrincipal accessToken: Jwt,
    ): ResponseEntity<Unit> {
        val callerId = callerId(accessToken)
        val conference = resolveForRead(parseConferenceId(conferenceId), callerId)
        if (conference.isEnded) {
            throw ConferenceEndedException()
        }
        requireModerator(conference, callerId)
        moderationService.end(conference)
        return ResponseEntity.noContent().build()
    }

    /**
     * №49 (api-contract.md 009 §2, T039): the full SET of the target's
     * four moderation flags — `200 ParticipantView` of the row AFTER
     * the write plus the live `online` flag observed by the immediate
     * `UpdateParticipant`/`MutePublishedTrack` application (SC-005).
     * The ladder: the shape 400s (the raw `conferenceId`, the blank
     * `{identity}`, the partial body) → the uniform 404 →
     * [requireModerator] → the service's `404 participant_not_found`
     * of the folded target. The target is ANY participant — another
     * moderator, the creator, the caller himself (Q21=A): the gate
     * never looks at the `{identity}` it is pointed at.
     */
    @PutMapping("/api/v1/conferences/{conferenceId}/participants/{identity}/restrictions")
    fun setRestrictions(
        @PathVariable conferenceId: String,
        @PathVariable identity: String,
        @RequestBody request: UpdateParticipantRestrictionsRequest,
        @AuthenticationPrincipal accessToken: Jwt,
    ): ParticipantView {
        val callerId = callerId(accessToken)
        val restrictions = request.validatedRestrictions()
        requireIdentity(identity)
        val conference = resolveForRead(parseConferenceId(conferenceId), callerId)
        requireModerator(conference, callerId)
        return viewOf(moderationService.setRestrictions(conference, identity, restrictions))
    }

    /**
     * №50 (api-contract.md 009 §2, T039): the role SET of the target —
     * `200 ParticipantView` with the persisted role (Q9=A) and the
     * live `online` flag of the metadata broadcast leg. The SAME
     * ladder as №49 with the `role` shape check (`400 invalid_role`);
     * elevation and demotion are one symmetric pair — moderators are
     * EQUAL, the creator is demotable, no «владелец» exists (Q21=A).
     */
    @PutMapping("/api/v1/conferences/{conferenceId}/participants/{identity}/role")
    fun setRole(
        @PathVariable conferenceId: String,
        @PathVariable identity: String,
        @RequestBody request: UpdateParticipantRoleRequest,
        @AuthenticationPrincipal accessToken: Jwt,
    ): ParticipantView {
        val callerId = callerId(accessToken)
        val role = request.validatedRole()
        requireIdentity(identity)
        val conference = resolveForRead(parseConferenceId(conferenceId), callerId)
        requireModerator(conference, callerId)
        return viewOf(moderationService.changeRole(conference, identity, role))
    }

    /**
     * №51 (api-contract.md 009 §2, T039): the kick — `204`, the
     * immediate `RemoveParticipant` of every live connection of the
     * target with NO row write (кик ≠ бан — the re-join by the
     * still-active link stays open). The SAME ladder as №49/№50; the
     * service owns the `404 participant_not_found` fold and lets a
     * media-plane failure surface as `503
     * conference_media_unavailable` (there is no durable fact to
     * outrank the disconnect — the ModerationService.kick contract).
     */
    @DeleteMapping("/api/v1/conferences/{conferenceId}/participants/{identity}")
    fun kick(
        @PathVariable conferenceId: String,
        @PathVariable identity: String,
        @AuthenticationPrincipal accessToken: Jwt,
    ): ResponseEntity<Unit> {
        val callerId = callerId(accessToken)
        requireIdentity(identity)
        val conference = resolveForRead(parseConferenceId(conferenceId), callerId)
        requireModerator(conference, callerId)
        moderationService.kick(conference, identity)
        return ResponseEntity.noContent().build()
    }

    private fun callerId(accessToken: Jwt): UUID = UUID.fromString(accessToken.subject)

    /**
     * The №48–№51 role gate (api-contract.md 009 §1, FR-008): the
     * CALLER's principal row must be `role='moderator'` — a plain
     * participant, a member who has not joined the conference yet and
     * an already-finalized roster (Q18=A) all answer the SAME `403
     * moderator_required` through [ModeratorRequiredException]. The
     * gate is TARGET-blind on purpose: moderators are equal and act
     * on anyone including each other and the creator (Q21=A), so no
     * `{identity}` — and no self-action — is ever refused here.
     */
    private fun requireModerator(
        conference: Conference,
        callerId: UUID,
    ) {
        val caller = participantRepository.findMember(conference.id, callerId)
        if (caller?.role != ParticipantRole.MODERATOR) {
            throw ModeratorRequiredException()
        }
    }

    /**
     * The №49/№50/№51 `ParticipantView` projection (api-contract.md §4):
     * the row AFTER the applied change with the just-persisted flags
     * and the `online` flag the service observed during the immediate
     * application — one object, the panel re-renders without a
     * follow-up read. [ParticipantView.name] is the guest's stored
     * display name or the member's `users.username` through
     * [MemberNameReader] (a guest row cannot exist without its name
     * and a member row cannot exist without its user — the V18
     * shape-CHECK; a `null` username is the invariant breach it is).
     */
    private fun viewOf(update: ModerationUpdate): ParticipantView {
        val row = update.participant
        return ParticipantView(
            identity = row.identity.value,
            name =
                when (row.identity) {
                    is ConferenceIdentity.Member ->
                        memberNames.usernameOf(row.identity.userId)
                            ?: error("participant ${row.identity.value} has no users row (the V18 invariant)")
                    is ConferenceIdentity.Guest -> row.displayName!!
                },
            role = participantRoleLabel(row.role),
            guest = row.isGuest,
            micRestricted = row.restrictions.micRestricted,
            cameraRestricted = row.restrictions.cameraRestricted,
            screenRestricted = row.restrictions.screenRestricted,
            speechRestricted = row.restrictions.speechRestricted,
            online = update.online,
        )
    }

    /**
     * The `{identity}` shape gate of №49/№50/№51 (api-contract.md 009
     * §2): a blank segment is the contract 400 `errors: {identity:
     * [malformed_request]}`; a MALFORMED but non-blank value is NOT
     * checked here — it folds inside [ModerationService] to the same
     * `404 participant_not_found` as an unknown one (the 006 privacy
     * precedent, nothing echoed).
     */
    private fun requireIdentity(raw: String) {
        if (raw.isBlank()) {
            throw InvalidParticipantIdentityException()
        }
    }

    /**
     * The №45/№46 resolution (api-contract.md 009 §1): the uniform 404 —
     * an unknown id and a non-member's id answer the SAME
     * `conference_not_found`, so neither the existence nor the roster of
     * a conference is disclosed to an outsider (the 006 privacy
     * precedent; the split 403 belongs to №44/№47 only).
     */
    private fun resolveForRead(
        conferenceId: UUID,
        callerId: UUID,
    ): Conference {
        val conference = conferenceRepository.findById(conferenceId) ?: throw ConferenceNotFoundException()
        if (!membership.isMember(conference.chatId, callerId)) {
            throw ConferenceNotFoundException()
        }
        return conference
    }

    /**
     * The resolved [ConferenceView] of №45 and the №44 `200 Existing`
     * leg: the mode re-read from the invite widget (the conference that
     * actually runs may differ from the submitted request) and the
     * LIVE connection count of the media plane; an ended conference
     * answers 0 without a round-trip (its room is gone by finalize).
     */
    private fun view(conference: Conference): ConferenceView =
        viewOf(
            conference = conference,
            mode = resolveMode(conference),
            activeParticipants =
                if (conference.isEnded) {
                    0
                } else {
                    liveKit.listParticipants(conference.roomName).size
                },
        )

    private fun viewOf(
        conference: Conference,
        mode: ConferenceMode,
        activeParticipants: Int,
    ): ConferenceView =
        ConferenceView(
            id = conference.id,
            chatId = conference.chatId,
            status = conferenceStatusLabel(conference.status),
            mode = conferenceModeLabel(mode),
            createdBy = conference.createdBy,
            createdAt = conference.createdAt,
            maxParticipants = conference.maxParticipants,
            activeParticipants = activeParticipants,
            startedAt = conference.startedAt,
            endedAt = conference.endedAt,
            endReason = conference.endReason?.let(::conferenceEndReasonLabel),
        )

    /**
     * The single mode source of every stored-conference view — the
     * invite widget payload of the chat history (data-model 009
     * §Сущность 4: the row stores no mode). A missing widget is a
     * broken invariant (it rides the create transaction, T017), not a
     * client answer.
     */
    private fun resolveMode(conference: Conference): ConferenceMode =
        inviteModes.modeOf(conference.id, conference.chatId)
            ?: error(
                "conference ${conference.id} has no invite widget in chat ${conference.chatId} " +
                    "(the T017 create-transaction invariant)",
            )

    /**
     * The №44 path gate: a non-UUID `chatId` segment is the contract
     * 400 `errors: {chatId: [invalid_uuid]}` — bound as a RAW string on
     * purpose (the [webchat.backend.chats.api.TypingController]
     * convention), so the refusal stays the typed problem of
     * [ConferencesExceptionHandler] instead of a framework conversion
     * error page.
     */
    private fun parseChatId(raw: String): UUID =
        try {
            UUID.fromString(raw)
        } catch (failure: IllegalArgumentException) {
            throw InvalidConferenceChatIdException(failure)
        }

    /** The `{conferenceId}` twin of [parseChatId]: `errors: {conferenceId: [invalid_uuid]}` (№45–№47). */
    private fun parseConferenceId(raw: String): UUID =
        try {
            UUID.fromString(raw)
        } catch (failure: IllegalArgumentException) {
            throw InvalidConferenceIdException(failure)
        }
}

/**
 * 400 (api-contract.md 009 №44): the PATH `chatId` of the create call
 * is not a UUID — `errors: {chatId: [invalid_uuid]}`. The submitted
 * value is never echoed (constitution V).
 */
class InvalidConferenceChatIdException(
    cause: IllegalArgumentException? = null,
) : RuntimeException("chatId must be a UUID", cause)

/**
 * 400 (api-contract.md 009 №45/№46/№47): the PATH `conferenceId` is
 * not a UUID — `errors: {conferenceId: [invalid_uuid]}`. The same
 * raw-string convention as [InvalidConferenceChatIdException]. The
 * submitted value is never echoed (constitution V).
 */
class InvalidConferenceIdException(
    cause: IllegalArgumentException? = null,
) : RuntimeException("conferenceId must be a UUID", cause)
