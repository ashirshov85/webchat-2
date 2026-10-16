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
}
