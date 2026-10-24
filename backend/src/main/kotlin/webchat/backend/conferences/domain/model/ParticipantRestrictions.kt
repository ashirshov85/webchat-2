package webchat.backend.conferences.domain.model

/**
 * A publishable LiveKit source kind (research §4/§5,
 * livekit-integration.md §3): the token grant `canPublishSources` and
 * the `UpdateParticipant` permission use the wire names
 * `microphone`/`camera`/`screen_share`/`screen_share_audio` — the
 * mapping to the SDK types lives in the adapters (T012/T018/T036), the
 * domain stays technology-free (constitution VIII DIP).
 */
enum class PublicationSource {
    MICROPHONE,
    CAMERA,
    SCREEN_SHARE,
    SCREEN_SHARE_AUDIO,
}

/**
 * The publish side of a participant's LiveKit grants computed from
 * moderation restrictions (research §4/§5,
 * livekit-integration.md §3): [canPublish] gates ANY publication
 * (`false` under the «право слова»), [canPublishSources] is the
 * allowed source set. `canSubscribe`/`canPublishData` are constants of
 * the contract (always `true`/`false` respectively) and admin grants
 * are never client-issued — neither is computed here.
 */
data class PublishGrants(
    val canPublish: Boolean,
    val canPublishSources: Set<PublicationSource>,
)

/**
 * The four moderation restrictions of a conference principal
 * (data-model 009 §Сущность 2, research §5): [micRestricted],
 * [cameraRestricted], [screenRestricted] remove the corresponding
 * source(s) — screen removes BOTH [PublicationSource.SCREEN_SHARE] and
 * [PublicationSource.SCREEN_SHARE_AUDIO] (Q22=A) — and
 * [speechRestricted] is «право слова»: it blocks ALL publications
 * (Q4=A). Changed only by the SET-semantics PUT №49 (T036), persisted
 * until the end of the conference (Q5=A) and re-applied verbatim at
 * every re-mint; a kick (№51) never writes here (кик ≠ бан).
 */
data class ParticipantRestrictions(
    val micRestricted: Boolean = false,
    val cameraRestricted: Boolean = false,
    val screenRestricted: Boolean = false,
    val speechRestricted: Boolean = false,
) {
    /** Point restriction predicate: which sources this state removes. */
    fun isRestricted(source: PublicationSource): Boolean =
        when (source) {
            PublicationSource.MICROPHONE -> micRestricted
            PublicationSource.CAMERA -> cameraRestricted
            PublicationSource.SCREEN_SHARE, PublicationSource.SCREEN_SHARE_AUDIO -> screenRestricted
        }

    /** The sources removed by this state — the `MutePublishedTrack` targets of T036. */
    fun restrictedSources(): Set<PublicationSource> = PublicationSource.entries.filter(::isRestricted).toSet()

    /**
     * THE single grants computation (research §4/§5, data-model 009
     * §Сущность 2) shared by the token mint (T018), the moderation
     * `UpdateParticipant` permission (T036) and the re-mint after
     * reconnect (Q5=A — restrictions reproduce in grants, invariant 3):
     * `speech_restricted ⟹ canPublish=false` (sources are then not
     * consulted — the «иначе» branch of the data model); otherwise
     * `canPublishSources = {mic, camera, screen_share, screen_share_audio} − restricted`.
     * T094: when the point restrictions cover EVERY source (all three
     * together — screen removes both screen kinds, Q22=A), the
     * subtraction remainder is empty, and an EMPTY canPublishSources in
     * LiveKit semantically means «every source allowed»
     * (livekit-integration §3) — the state therefore encodes as
     * `canPublish=false` (the source set is then not consulted) instead
     * of lifting the restrictions.
     */
    fun grants(): PublishGrants {
        val restricted = restrictedSources()
        val allPointSourcesRemoved = PublicationSource.entries.all(restricted::contains)
        return if (speechRestricted || allPointSourcesRemoved) {
            PublishGrants(canPublish = false, canPublishSources = emptySet())
        } else {
            PublishGrants(
                canPublish = true,
                canPublishSources = PublicationSource.entries.toSet() - restricted,
            )
        }
    }
}
