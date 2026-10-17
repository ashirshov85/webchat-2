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
import webchat.backend.conferences.domain.model.ParticipantRole
import webchat.backend.conferences.domain.port.LiveKitAdminClient
import webchat.backend.conferences.domain.port.LiveKitParticipant
import webchat.backend.conferences.domain.port.ParticipantRepository

/**
 * The shared №49/№50 answer shape of [ModerationService]: the
 * principal row AFTER the SET write (the `ParticipantView` source of
 * the T039 controller — identity/name/role/guest plus the just
 * persisted flags) and the [online] flag of the media plane observed
 * during the immediate application (a member principal is online with
 * ANY live connection — two tabs of one user are two connections,
 * edge Q10). One object, one round-trip: the panel re-renders without
 * a follow-up №45/№46 read.
 */
data class ModerationUpdate(
    val participant: ConferenceParticipant,
    val online: Boolean,
)

/**
 * T036/T037 (009, US3) — the server-side moderation legs of the
 * `conferences` API over the two-step discipline of research.md §5:
 * the durable SET write plus the IMMEDIATE application to the live
 * connection(s):
 *
 *  * **№49** [setRestrictions] — the four-flag SET of the
 *    moderation restrictions;
 *  * **№50** [changeRole] — the role SET, persistent until the end
 *    of the conference (Q9=A);
 *  * **№51** [kick] — the immediate disconnect with NO row write
 *    (кик ≠ бан).
 *
 * 1. **the durable SET** — [ParticipantRepository.updateRestrictions]
 *    / [ParticipantRepository.updateRole]: the full body overwrites
 *    the principal row (PUT semantics — repeating the same body
 *    rewrites the same values, idempotent), and the ROW is the
 *    persistence itself: restrictions and role live until the end of
 *    the conference and reproduce in the grants/metadata of every
 *    re-mint (Q5/Q9/Q22=A, invariant 3 of data-model 009). A guest
 *    row is a legit target of the CURRENT connection only — his
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
 *    (`{"role","guest","inviteId"?}` — the UI broadcast surface),
 *    then `MutePublishedTrack` for every already-published track of
 *    each point-restricted source
 *    ([ParticipantRestrictions.restrictedSources] — screen removes
 *    BOTH screen kinds, Q22=A; requires `enable_remote_unmute`,
 *    livekit-integration.md §1). The «право слова» mutes nothing
 *    itself: `canPublish=false` already gates the whole publish side
 *    (Q4=A). The №50 role push rides the SAME `UpdateParticipant`:
 *    the metadata change broadcasts `ParticipantMetadataChanged` to
 *    the UI, the grants recompute VERBATIM (the role writes no
 *    restriction) and NO track is muted. LiveKit broadcasts the
 *    permission change to the client
 *    (`ParticipantPermissionsChanged`) — the restricted participant
 *    sees his toggles lock live (FR-007/FR-008, T043).
 *
 * The application is best-effort AFTER the durable fact (the T017
 * post-commit rule): the contract's №49/№50 tables define no
 * media-failure code, and the row is the source of truth anyway — a
 * failing plane is logged (ids only, constitution V) and left to
 * converge: every re-mint re-applies the state and a repeated
 * №49/№50 retries the live leg idempotently. The №51 kick is the
 * DELIBERATE exception — see [kick].
 *
 * The moderator gate itself (`403 moderator_required`) and the №48–№51
 * HTTP surface stay with the T039 controller — moderators are equal
 * and act on ANYONE including each other and the creator (Q21=A), so
 * the gate needs nothing here. The №48 end of T038 joins this class.
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
    ): ModerationUpdate {
        val principal =
            ConferenceIdentity.parsePrincipal(targetIdentity)
                ?: throw ParticipantNotFoundException()
        val row =
            participants.updateRestrictions(conference.id, principal.value, restrictions)
                ?: throw ParticipantNotFoundException()
        metrics.countModerationAction(ConferenceMetrics.ModerationAction.SET_RESTRICTIONS)
        return ModerationUpdate(
            participant = row,
            online = applyToLiveConnections(conference, row, mutePointRestrictedSources = true),
        )
    }

    /**
     * №50 (api-contract.md 009 §2): the SET write of the target's
     * role — the SAME two-step discipline as [setRestrictions]
     * (research.md §5), with the persistence being the row itself:
     * the role lives until the end of the conference and reproduces
     * in the metadata of every re-mint, so a moderator KEEPS his
     * rights across reconnect (Q9=A); no auto-transfer exists — a
     * conference without moderators keeps living until they return
     * or the conference ends (spec.md Q9).
     *
     * The immediate application is the metadata broadcast: every LIVE
     * connection of the principal gets `UpdateParticipant` with the
     * metadata rebuilt from the row — the `role` change rides
     * `ParticipantMetadataChanged` to everyone in the room
     * (livekit-integration.md §4). The grants ride along UNCHANGED
     * (`row.grants()` recomputes to the very same permission — the
     * role writes no restriction) and NO `MutePublishedTrack` is
     * issued: a previously muted track stays muted under the
     * unchanged restrictions.
     *
     * Moderators are equal and promote/demote ANYONE including each
     * other and the creator (Q21=A — the gate stays with the T039
     * controller). [targetIdentity] folds exactly as in №49;
     * `ThrowsCount` suppressed for the same single-refusal reason.
     */
    @Suppress("ThrowsCount")
    fun changeRole(
        conference: Conference,
        targetIdentity: String,
        role: ParticipantRole,
    ): ModerationUpdate {
        val principal =
            ConferenceIdentity.parsePrincipal(targetIdentity)
                ?: throw ParticipantNotFoundException()
        val row =
            participants.updateRole(conference.id, principal.value, role)
                ?: throw ParticipantNotFoundException()
        metrics.countModerationAction(ConferenceMetrics.ModerationAction.SET_ROLE)
        return ModerationUpdate(
            participant = row,
            online = applyToLiveConnections(conference, row, mutePointRestrictedSources = false),
        )
    }

    /**
     * №51 (api-contract.md 009 §2): the kick — the immediate
     * server-side disconnect of EVERY live connection of the target
     * (`RemoveParticipant`, research.md §5 п. 4) with NO row write of
     * any kind: кик ≠ бан — the principal row survives VERBATIM with
     * its role and restrictions (data-model 009 §Сущность 2), so the
     * kicked participant re-enters by the still-active link (a member
     * re-joins through №47, a guest through №55 while the invite
     * lives — the multi-use link stays valid, Q2=A) and the re-mint
     * re-computes his grants from the untouched row. The radical
     * guest measure is the №53 revocation, never the kick.
     *
     * The target resolution is the SAME fold as №49/№50: malformed
     * and unknown identities answer [ParticipantNotFoundException]
     * (`404 participant_not_found`) with nothing disconnected and
     * nothing counted.
     *
     * Unlike №49/№50 there is NO durable fact outranking the media
     * leg — the disconnect IS the whole action — so a media-plane
     * failure PROPAGATES as
     * [ConferenceMediaUnavailableException] (the mapped
     * `503 conference_media_unavailable` of T019): a swallowed
     * failure would answer `204` while the target stayed connected,
     * with nothing left to converge (no row carries the kick). The
     * idempotent №51 retry IS the convergence — an absent identity
     * is a success no-op, so a partial kick completed by the retry
     * stays safe.
     *
     * `ThrowsCount` suppressed: the two throw statements are the same
     * single №51 refusal (`404 participant_not_found`).
     */
    @Suppress("ThrowsCount")
    fun kick(
        conference: Conference,
        targetIdentity: String,
    ) {
        val principal =
            ConferenceIdentity.parsePrincipal(targetIdentity)
                ?: throw ParticipantNotFoundException()
        val row =
            participants.findByIdentity(conference.id, principal.value)
                ?: throw ParticipantNotFoundException()
        connectionsOf(conference, row).forEach { connection ->
            liveKit.removeParticipant(conference.roomName, connection.identity)
        }
        metrics.countModerationAction(ConferenceMetrics.ModerationAction.KICK)
    }

    /**
     * The step-2 pipeline over the room's live roster: resolve the
     * principal's connections, push the recomputed permission+metadata
     * and — for the №49 restrictions SET only
     * ([mutePointRestrictedSources] — the №50 role push mutes nothing,
     * the grants recompute verbatim) — mute the published tracks of
     * every point-restricted source. Returns the observed `online`
     * flag; the whole leg is best-effort AFTER the committed SET (see
     * the class KDoc) — a failure answers `false` and never fails the
     * durable №49/№50 answer.
     *
     * `TooGenericExceptionCaught` suppressed: the committed row
     * outranks the media round-trips; convergence is documented.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun applyToLiveConnections(
        conference: Conference,
        row: ConferenceParticipant,
        mutePointRestrictedSources: Boolean,
    ): Boolean =
        try {
            val connections = connectionsOf(conference, row)
            connections.forEach { connection ->
                applyToConnection(conference, row, connection, mutePointRestrictedSources)
            }
            connections.isNotEmpty()
        } catch (failure: Exception) {
            log.warn(
                "the live application of moderation state for participant <{}> of conference <{}> failed " +
                    "after the SET row committed; the persisted state re-applies at every re-mint " +
                    "and a repeated №49/№50 retries the leg idempotently: {}",
                row.identity.value,
                conference.id,
                failure.message,
            )
            false
        }

    /**
     * The per-connection leg of the step-2 push: the recomputed
     * permission+metadata and — for the №49 restrictions SET only
     * ([mutePointRestrictedSources]) — the mute of every published
     * track of each point-restricted source.
     */
    private fun applyToConnection(
        conference: Conference,
        row: ConferenceParticipant,
        connection: LiveKitParticipant,
        mutePointRestrictedSources: Boolean,
    ) {
        liveKit.updateParticipant(
            room = conference.roomName,
            identity = connection.identity,
            permission = row.grants(),
            metadata = metadataFor(row, connection),
        )
        if (mutePointRestrictedSources) {
            row.restrictions.restrictedSources().forEach { source ->
                liveKit.mutePublishedTrack(conference.roomName, connection.identity, source)
            }
        }
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
