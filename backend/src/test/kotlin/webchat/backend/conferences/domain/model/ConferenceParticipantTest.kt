package webchat.backend.conferences.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID

/**
 * Unit-level mirror of the ConferenceParticipant invariants mandated
 * for T008 (data-model 009 §Сущность 2): the V18 shape-CHECK (a guest
 * must carry the entered name, a member must not), the creator's
 * default moderator role (FR-008), the guest-per-connection identity
 * (Q7=A) and the grants delegation to [ParticipantRestrictions] (the
 * re-mint persistence Q5=A is asserted by the IT of T040).
 */
class ConferenceParticipantTest {
    @Test
    fun `member principal carries the u-prefix and no display name`() {
        val participant = ConferenceParticipant.member(PARTICIPANT_ID, CONFERENCE_ID, ALICE, FIRST_SEEN)

        assertThat(participant.identity).isEqualTo(ConferenceIdentity.Member(ALICE))
        assertThat(participant.userId).isEqualTo(ALICE)
        assertThat(participant.isGuest).isFalse
        assertThat(participant.displayName).isNull()
        assertThat(participant.role).isEqualTo(ParticipantRole.PARTICIPANT)
        assertThat(participant.restrictions).isEqualTo(ParticipantRestrictions())
    }

    @Test
    fun `the creator row defaults to moderator`() {
        val creator =
            ConferenceParticipant.member(
                PARTICIPANT_ID,
                CONFERENCE_ID,
                ALICE,
                FIRST_SEEN,
                role = ParticipantRole.MODERATOR,
            )

        assertThat(creator.role).isEqualTo(ParticipantRole.MODERATOR)
    }

    @Test
    fun `guest principal carries the g-prefix and the entered name`() {
        val guest =
            ConferenceParticipant.guest(PARTICIPANT_ID, CONFERENCE_ID, CONN_ID, "Carol-from-outside", FIRST_SEEN)

        assertThat(guest.identity).isEqualTo(ConferenceIdentity.Guest(CONN_ID))
        assertThat(guest.userId).isNull()
        assertThat(guest.isGuest).isTrue
        assertThat(guest.displayName).isEqualTo("Carol-from-outside")
        assertThat(guest.role).isEqualTo(ParticipantRole.PARTICIPANT)
    }

    @Test
    fun `a guest principal without a name violates the shape-CHECK`() {
        assertThrows<IllegalArgumentException> {
            ConferenceParticipant(
                id = PARTICIPANT_ID,
                conferenceId = CONFERENCE_ID,
                identity = ConferenceIdentity.guest(CONN_ID),
                displayName = null,
                firstSeenAt = FIRST_SEEN,
            )
        }
        assertThrows<IllegalArgumentException> {
            ConferenceParticipant(
                id = PARTICIPANT_ID,
                conferenceId = CONFERENCE_ID,
                identity = ConferenceIdentity.guest(CONN_ID),
                displayName = "   ",
                firstSeenAt = FIRST_SEEN,
            )
        }
    }

    @Test
    fun `a member principal with a display name violates the shape-CHECK`() {
        assertThrows<IllegalArgumentException> {
            ConferenceParticipant(
                id = PARTICIPANT_ID,
                conferenceId = CONFERENCE_ID,
                identity = ConferenceIdentity.member(ALICE),
                displayName = "Alice",
                firstSeenAt = FIRST_SEEN,
            )
        }
    }

    @Test
    fun `grants delegate to the persisted restrictions`() {
        val restricted =
            ConferenceParticipant
                .member(PARTICIPANT_ID, CONFERENCE_ID, ALICE, FIRST_SEEN)
                .copy(restrictions = ParticipantRestrictions(speechRestricted = true))

        assertThat(restricted.grants()).isEqualTo(ParticipantRestrictions(speechRestricted = true).grants())
        assertThat(restricted.grants().canPublish).isFalse
    }

    companion object {
        private val PARTICIPANT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b2")
        private val CONFERENCE_ID = UUID.fromString("00000000-0000-0000-0000-00000000000f")
        private val ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001")
        private val CONN_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa")
        private val FIRST_SEEN = Instant.parse("2026-10-16T10:00:05Z")
    }
}
