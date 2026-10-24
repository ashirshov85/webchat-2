package webchat.backend.conferences.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Unit-level mirror of the single grants computation mandated for T008
 * (research §4/§5, data-model 009 §Сущность 2): «право слова» blocks
 * ALL publications (Q4=A), a source restriction removes its source(s) —
 * screen removes BOTH screen_share and screen_share_audio (Q22=A) — and
 * the unrestricted state grants the full source set. T094: ALL THREE
 * point restrictions together also encode as canPublish=false — an
 * empty canPublishSources remainder would mean «everything allowed»
 * in LiveKit. The re-mint reproduction (Q5=A, invariant 3) is the IT
 * of T040.
 */
class ParticipantRestrictionsTest {
    @Test
    fun `unrestricted principal grants every source`() {
        val grants = ParticipantRestrictions().grants()

        assertThat(grants.canPublish).isTrue
        assertThat(grants.canPublishSources).containsExactlyInAnyOrder(
            PublicationSource.MICROPHONE,
            PublicationSource.CAMERA,
            PublicationSource.SCREEN_SHARE,
            PublicationSource.SCREEN_SHARE_AUDIO,
        )
    }

    @Test
    fun `speech restriction blocks every publication`() {
        val grants = ParticipantRestrictions(speechRestricted = true).grants()

        assertThat(grants.canPublish).isFalse
        assertThat(grants.canPublishSources).isEmpty()
    }

    @Test
    fun `mic restriction removes only the microphone`() {
        val grants = ParticipantRestrictions(micRestricted = true).grants()

        assertThat(grants.canPublish).isTrue
        assertThat(grants.canPublishSources).containsExactlyInAnyOrder(
            PublicationSource.CAMERA,
            PublicationSource.SCREEN_SHARE,
            PublicationSource.SCREEN_SHARE_AUDIO,
        )
    }

    @Test
    fun `camera restriction removes only the camera`() {
        val grants = ParticipantRestrictions(cameraRestricted = true).grants()

        assertThat(grants.canPublish).isTrue
        assertThat(grants.canPublishSources).containsExactlyInAnyOrder(
            PublicationSource.MICROPHONE,
            PublicationSource.SCREEN_SHARE,
            PublicationSource.SCREEN_SHARE_AUDIO,
        )
    }

    @Test
    fun `screen restriction removes both screen sources`() {
        val grants = ParticipantRestrictions(screenRestricted = true).grants()

        assertThat(grants.canPublish).isTrue
        assertThat(grants.canPublishSources).containsExactlyInAnyOrder(
            PublicationSource.MICROPHONE,
            PublicationSource.CAMERA,
        )
    }

    @Test
    fun `all three point restrictions encode as canPublish=false`() {
        // T094: mic+camera+screen together empty the subtraction remainder,
        // but an EMPTY canPublishSources in LiveKit means «every source
        // allowed» (livekit-integration §3) — the grants therefore encode
        // the state as canPublish=false (the source set is then not
        // consulted), NOT as an empty «allowed everything» set.
        val grants =
            ParticipantRestrictions(
                micRestricted = true,
                cameraRestricted = true,
                screenRestricted = true,
            ).grants()

        assertThat(grants.canPublish).isFalse
        assertThat(grants.canPublishSources).isEmpty()
    }

    @Test
    fun `speech restriction dominates remaining source restrictions`() {
        val grants =
            ParticipantRestrictions(
                micRestricted = true,
                speechRestricted = true,
            ).grants()

        assertThat(grants.canPublish).isFalse
        assertThat(grants.canPublishSources).isEmpty()
    }

    @Test
    fun `restrictedSources mirrors the point predicates`() {
        val restrictions =
            ParticipantRestrictions(
                micRestricted = true,
                screenRestricted = true,
            )

        assertThat(restrictions.restrictedSources()).containsExactlyInAnyOrder(
            PublicationSource.MICROPHONE,
            PublicationSource.SCREEN_SHARE,
            PublicationSource.SCREEN_SHARE_AUDIO,
        )
        assertThat(restrictions.isRestricted(PublicationSource.CAMERA)).isFalse
    }
}
