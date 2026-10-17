package webchat.backend.conferences.domain.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import webchat.backend.conferences.ConferenceMetrics
import webchat.backend.conferences.api.dto.participantRoleLabel
import webchat.backend.conferences.domain.model.Conference
import webchat.backend.conferences.domain.model.ConferenceIdentity
import webchat.backend.conferences.domain.model.ConferenceParticipant
import webchat.backend.conferences.domain.model.ParticipantRestrictions
import webchat.backend.conferences.domain.port.LiveKitAdminClient
import webchat.backend.conferences.domain.port.LiveKitParticipant
import webchat.backend.conferences.domain.port.ParticipantRepository

/**
 * The №49 answer shape of [ModerationService.setRestrictions]: the
 * principal row AFTER the SET write (the `ParticipantView` source of
 * the T039 controller — identity/name/role/guest plus the four flags
 * just persisted) and the [online] flag of the media plane observed
 * during the immediate application (a member principal is online with
 * ANY live connection — two tabs of one user are two connections,
 * edge Q10). One object, one round-trip: the panel re-renders without
 * a follow-up №45/№46 read.
 */
data class RestrictionsUpdate(
    val participant: ConferenceParticipant,
    val online: Boolean,
)

/**
 * T036 (009, US3) — the №49 moderation leg of
 * `PUT /api/v1/conferences/{conferenceId}/participants/{identity}/restrictions`:
 * the SET-state write of the four moderation flags plus their
 * IMMEDIATE application to the live connection(s) — research.md §5,
 * the two-step discipline verbatim:
 *
 * 1. **the durable SET** — [ParticipantRepository.updateRestrictions]:
 *    the full four-flag body overwrites the principal row (PUT
 *    semantics — repeating the same body rewrites the same values,
 *    idempotent), and the ROW is the persistence itself: restrictions
 *    live until the end of the conference and reproduce in the grants
 *    of every re-mint (Q5/Q22=A, invariant 3 of data-model 009). A
 *    guest row is a legit target of the CURRENT connection only — his
 *    reconnect is a new row with fresh defaults (Q7=A, research §5
 *    п. 6). Unknown target → [ParticipantNotFoundException] (`404
 *    participant_not_found` — no principal, never joined or already
 *    finalized, indistinguishable by design).
 *
 * 2. **the immediate application** (SC-005, FR-008) — after the SET
 *    has committed, every LIVE connection of the principal gets
 *    `UpdateParticipant` with the grants recomputed from the row
 *    (`speech_restricted ⟹ canPublish=false`, sources minus
 *    restricted — [ConferenceParticipant.grants], the SAME single
 *    computation as the mint, Q5=A) and the full metadata JSON
 *    (`{"role","guest","inviteId"?}` — the UI broadcast surface), then
 *    `MutePublishedTrack` for every already-published track of each
 *    point-restricted source
 *    ([ParticipantRestrictions.restrictedSources] — screen removes
 *    BOTH screen kinds, Q22=A; requires `enable_remote_unmute`,
 *    livekit-integration.md §1). The «право слова» mutes nothing
 *    itself: `canPublish=false` already gates the whole publish side
 *    (Q4=A). LiveKit broadcasts the permission change to the client
 *    (`ParticipantPermissionsChanged`) — the restricted participant
 *    sees his toggles lock live (FR-007/FR-008, T043).
 *
 * The application is best-effort AFTER the durable fact (the T017
 * post-commit rule): the contract's №49 table defines no media-failure
 * code, and the row is the source of truth anyway — a failing plane is
 * logged (ids only, constitution V) and left to converge: every re-mint
 * re-applies the state and a repeated №49 retries the live leg
 * idempotently.
 *
 * The moderator gate itself (`403 moderator_required`) and the №48–№51
 * HTTP surface stay with the T039 controller — moderators are equal
 * and act on ANYONE including each other and the creator (Q21=A), so
 * the gate needs nothing here. The №50 role change and №51 kick of
 * T037 and the №48 end of T038 join this class.
 */
@Service
class ModerationService(
    private val participants: ParticipantRepository,
    private val liveKit: LiveKitAdminClient,
    private val metrics: ConferenceMetrics,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(ModerationService::class.java)

    /**
     * №49 (api-contract.md 009 §2): the full SET of the target's four
     * moderation flags — [targetIdentity] is the RAW `{identity}` path
     * segment: a principal prefix `u:{userId}`/`g:{connId}` (the
     * `ParticipantView.identity` a panel re-submits) OR a full member
     * connection identity `u:{userId}:{connId}` (the roster key the
     * LiveKit-driven panel of T041 carries) — folded to the principal
     * by [ConferenceIdentity.parsePrincipal]; malformed input converges
     * to the SAME [ParticipantNotFoundException] as an unknown one (the
     * 006 privacy precedent, nothing echoed).
     *
     * `ThrowsCount` suppressed: the two throw statements ARE the same
     * single №49 refusal (`404 participant_not_found`) of the contract
     * table — the fold and the row-write legs.
     */
    @Suppress("ThrowsCount")
    fun setRestrictions(
        conference: Conference,
        targetIdentity: String,
        restrictions: ParticipantRestrictions,
    ): RestrictionsUpdate {
        val principal =
            ConferenceIdentity.parsePrincipal(targetIdentity)
                ?: throw ParticipantNotFoundException()
        val row =
            participants.updateRestrictions(conference.id, principal.value, restrictions)
                ?: throw ParticipantNotFoundException()
        metrics.countModerationAction(ConferenceMetrics.ModerationAction.SET_RESTRICTIONS)
        return RestrictionsUpdate(
            participant = row,
            online = applyToLiveConnections(conference, row),
        )
    }

    /**
     * The step-2 pipeline over the room's live roster: resolve the
     * principal's connections, push the recomputed permission+metadata
     * and mute the published tracks of every point-restricted source.
     * Returns the observed `online` flag; the whole leg is best-effort
     * AFTER the committed SET (see the class KDoc) — a failure answers
     * `false` and never fails the durable №49 answer.
     *
     * `TooGenericExceptionCaught` suppressed: the committed row
     * outranks the media round-trips; convergence is documented.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun applyToLiveConnections(
        conference: Conference,
        row: ConferenceParticipant,
    ): Boolean =
        try {
            val connections = connectionsOf(conference, row)
            connections.forEach { connection ->
                liveKit.updateParticipant(
                    room = conference.roomName,
                    identity = connection.identity,
                    permission = row.grants(),
                    metadata = metadataFor(row, connection),
                )
                row.restrictions.restrictedSources().forEach { source ->
                    liveKit.mutePublishedTrack(conference.roomName, connection.identity, source)
                }
            }
            connections.isNotEmpty()
        } catch (failure: Exception) {
            log.warn(
                "the live application of restrictions for participant <{}> of conference <{}> failed " +
                    "after the SET row committed; the persisted state re-applies at every re-mint " +
                    "and a repeated №49 retries the leg idempotently: {}",
                row.identity.value,
                conference.id,
                failure.message,
            )
            false
        }

    /**
     * The live connections of the principal within the room: EVERY
     * member connection by the `u:{userId}:` prefix (two tabs of one
     * user are two connections, edge Q10 — each needs its own
     * `UpdateParticipant`/`MutePublishedTrack`) and the exact
     * `g:{connId}` of a guest (his row and connection identity are
     * one, research §4). Other principals never match and stay
     * untouched.
     */
    private fun connectionsOf(
        conference: Conference,
        row: ConferenceParticipant,
    ): List<LiveKitParticipant> =
        liveKit.listParticipants(conference.roomName).filter { connection ->
            when (val principal = row.identity) {
                is ConferenceIdentity.Member ->
                    ConferenceIdentity.isMemberConnectionOf(connection.identity, principal.userId)
                is ConferenceIdentity.Guest -> connection.identity == principal.value
            }
        }

    /**
     * The full participant metadata pushed by `UpdateParticipant`
     * (livekit-integration.md §3): rebuilt from the ROW — the single
     * source of truth, so a stale live copy self-heals on every
     * moderation push — with the guest `inviteId` preserved from the
     * CURRENT live metadata: it rides the №55 mint only (T046) and
     * exists nowhere in the conferences tables, yet MUST survive every
     * later metadata rewrite — the №53 revocation disconnect matches
     * guests by it (livekit-integration.md §6.1).
     */
    private fun metadataFor(
        row: ConferenceParticipant,
        connection: LiveKitParticipant,
    ): String {
        val metadata =
            objectMapper
                .createObjectNode()
                .put(METADATA_ROLE, participantRoleLabel(row.role))
                .put(METADATA_GUEST, row.isGuest)
        if (row.isGuest) {
            inviteIdOf(connection.metadata)?.let { metadata.put(METADATA_INVITE_ID, it) }
        }
        return metadata.toString()
    }

    /**
     * The `inviteId` of a live guest metadata string, or `null` for
     * unset/malformed input — the metadata is server-minted JSON (the
     * T018/T046 mints), so a parse failure is a broken invariant the
     * push must survive, not a moderation refusal.
     *
     * `TooGenericExceptionCaught` suppressed: any parse failure of a
     * foreign string converges to `null` — the push degrades to the
     * role/guest core, never throws.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun inviteIdOf(rawMetadata: String?): String? {
        val node: JsonNode? =
            if (rawMetadata == null) {
                null
            } else {
                try {
                    objectMapper.readTree(rawMetadata)
                } catch (_: Exception) {
                    null
                }
            }
        return node?.path(METADATA_INVITE_ID)?.takeIf { it.isTextual }?.asText()
    }

    private companion object {
        const val METADATA_ROLE = "role"

        const val METADATA_GUEST = "guest"

        const val METADATA_INVITE_ID = "inviteId"
    }
}
