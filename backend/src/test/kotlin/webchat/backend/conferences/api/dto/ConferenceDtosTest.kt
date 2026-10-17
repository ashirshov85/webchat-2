package webchat.backend.conferences.api.dto

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import webchat.backend.conferences.domain.model.ConferenceEndReason
import webchat.backend.conferences.domain.model.ConferenceStatus
import webchat.backend.conferences.domain.model.ParticipantRole

/**
 * Unit mirror of the T015 wire codec (api-contract.md §4): the №44
 * `mode` request validation (`400 invalid_mode` carrier — the handler
 * mapping itself is T019) and the lowercase schema labels of the
 * ConferenceView/MeView/ParticipantView enums. The full №44/№45/№46/№47
 * request/response cycles are the IT set of T021.
 */
class ConferenceDtosTest {
    @Test
    fun `video and audio labels parse into the mode enum`() {
        assertThat(CreateConferenceRequest("video").validatedMode()).isEqualTo(ConferenceMode.VIDEO)
        assertThat(CreateConferenceRequest("audio").validatedMode()).isEqualTo(ConferenceMode.AUDIO)
    }

    @Test
    fun `absent mode is rejected as invalid_mode`() {
        assertThatThrownBy { CreateConferenceRequest(mode = null).validatedMode() }
            .isInstanceOf(InvalidConferenceModeException::class.java)
    }

    @Test
    fun `unknown mode label is rejected as invalid_mode without echo`() {
        assertThatThrownBy { CreateConferenceRequest("VIDEO").validatedMode() }
            .isInstanceOf(InvalidConferenceModeException::class.java)
            .hasMessageNotContaining("VIDEO")
    }

    @Test
    fun `schema enum labels are lowercase`() {
        assertThat(conferenceModeLabel(ConferenceMode.VIDEO)).isEqualTo("video")
        assertThat(conferenceModeLabel(ConferenceMode.AUDIO)).isEqualTo("audio")
        assertThat(conferenceStatusLabel(ConferenceStatus.CREATED)).isEqualTo("created")
        assertThat(conferenceStatusLabel(ConferenceStatus.ACTIVE)).isEqualTo("active")
        assertThat(conferenceStatusLabel(ConferenceStatus.ENDED)).isEqualTo("ended")
        assertThat(participantRoleLabel(ParticipantRole.MODERATOR)).isEqualTo("moderator")
        assertThat(participantRoleLabel(ParticipantRole.PARTICIPANT)).isEqualTo("participant")
    }

    @Test
    fun `end reason labels match the contract snake_case enum`() {
        assertThat(ConferenceEndReason.entries.map(::conferenceEndReasonLabel)).containsExactly(
            "last_left",
            "moderator",
            "max_duration",
            "room_lost",
            "chat_deleted",
        )
    }

    @Test
    fun `invite info reason labels match the contract enum`() {
        assertThat(InviteInfoReason.entries.map(::inviteInfoReasonLabel)).containsExactly(
            "expired",
            "revoked",
            "ended",
        )
    }

    @Test
    fun `guest name is server-trimmed and admitted up to the configured max`() {
        assertThat(GuestJoinRequest(inviteToken = "nonce", name = "  carol  ").validatedName(50))
            .overridingErrorMessage("the server trim runs before the length gate (Q7=A)")
            .isEqualTo("carol")
        assertThat(GuestJoinRequest(inviteToken = "nonce", name = "c".repeat(50)).validatedName(50)).hasSize(50)
        assertThat(GuestJoinRequest(inviteToken = "nonce", name = "  ".repeat(25) + "c").validatedName(50))
            .isEqualTo("c")
    }

    @Test
    fun `absent and blank guest names are rejected as name_invalid`() {
        assertThatThrownBy { GuestJoinRequest(inviteToken = "nonce", name = null).validatedName(50) }
            .isInstanceOf(InvalidGuestNameException::class.java)
        assertThatThrownBy { GuestJoinRequest(inviteToken = "nonce", name = "   ").validatedName(50) }
            .isInstanceOf(InvalidGuestNameException::class.java)
    }

    @Test
    fun `an over-length guest name is rejected as name_invalid without echo`() {
        val tooLong = "c".repeat(51)

        assertThatThrownBy { GuestJoinRequest(inviteToken = "nonce", name = tooLong).validatedName(50) }
            .isInstanceOf(InvalidGuestNameException::class.java)
            .hasMessageNotContaining(tooLong)
    }

    @Test
    fun `the trim happens before the length gate - a padded max-length name stays valid`() {
        val padded = "  " + "c".repeat(50) + "  "

        assertThat(GuestJoinRequest(inviteToken = "nonce", name = padded).validatedName(50)).hasSize(50)
    }
}
