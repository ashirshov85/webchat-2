package webchat.backend.conferences.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Unit-level mirror of the identity parsing mandated for T008
 * (research §4/§13, data-model 009 §Сущность 2): stored principal
 * prefixes `u:{userId}`/`g:{connId}`, the full member connection
 * identity `u:{userId}:{connId}` folding back to its principal (the
 * webhook row lookup), the FR-003 removal prefix predicate of T020,
 * and the never-throw contract for malformed external input.
 */
class ConferenceIdentityTest {
    @Test
    fun `member principal value is the stored prefix`() {
        val member = ConferenceIdentity.member(ALICE)

        assertThat(member.value).isEqualTo("u:$ALICE")
        assertThat((member as ConferenceIdentity.Member).userId).isEqualTo(ALICE)
    }

    @Test
    fun `guest principal value is the connection identity itself`() {
        val guest = ConferenceIdentity.guest(CONN_ID)

        assertThat(guest.value).isEqualTo("g:$CONN_ID")
        assertThat((guest as ConferenceIdentity.Guest).connId).isEqualTo(CONN_ID)
    }

    @Test
    fun `member connection identity embeds a fresh connId per mint`() {
        val connection = ConferenceIdentity.member(ALICE).connectionIdentity(CONN_ID)

        assertThat(connection).isEqualTo("u:$ALICE:$CONN_ID")
    }

    @Test
    fun `parses stored principal prefixes`() {
        assertThat(ConferenceIdentity.parsePrincipal("u:$ALICE"))
            .isEqualTo(ConferenceIdentity.Member(ALICE))
        assertThat(ConferenceIdentity.parsePrincipal("g:$CONN_ID"))
            .isEqualTo(ConferenceIdentity.Guest(CONN_ID))
    }

    @Test
    fun `a full member connection identity folds to its principal for the webhook row lookup`() {
        val principal = ConferenceIdentity.parsePrincipal("u:$ALICE:$CONN_ID")

        assertThat(principal).isEqualTo(ConferenceIdentity.Member(ALICE))
    }

    @Test
    fun `connection identity round-trips to the issuing principal`() {
        val connection = ConferenceIdentity.member(BOB).connectionIdentity(CONN_ID)

        assertThat(ConferenceIdentity.parsePrincipal(connection)).isEqualTo(ConferenceIdentity.member(BOB))
    }

    @Test
    fun `malformed identities yield null instead of throwing`() {
        assertThat(ConferenceIdentity.parsePrincipal("")).isNull()
        assertThat(ConferenceIdentity.parsePrincipal("u")).isNull()
        assertThat(ConferenceIdentity.parsePrincipal("u:")).isNull()
        assertThat(ConferenceIdentity.parsePrincipal("x:$ALICE")).isNull()
        assertThat(ConferenceIdentity.parsePrincipal("u:not-a-uuid")).isNull()
        assertThat(ConferenceIdentity.parsePrincipal("g:not-a-uuid")).isNull()
        assertThat(ConferenceIdentity.parsePrincipal("u:$ALICE:not-a-uuid")).isNull()
        assertThat(ConferenceIdentity.parsePrincipal("g:$CONN_ID:${CONN_ID}")).isNull()
        assertThat(ConferenceIdentity.parsePrincipal("u:$ALICE:$CONN_ID:extra")).isNull()
        assertThat(ConferenceIdentity.parsePrincipal("U:$ALICE")).isNull()
    }

    @Test
    fun `member connection predicate matches only that user's connections`() {
        val connection = "u:$ALICE:$CONN_ID"

        assertThat(ConferenceIdentity.isMemberConnectionOf(connection, ALICE)).isTrue
        assertThat(ConferenceIdentity.isMemberConnectionOf(connection, BOB)).isFalse
        assertThat(ConferenceIdentity.isMemberConnectionOf("g:$CONN_ID", ALICE)).isFalse
    }

    @Test
    fun `the longest identity fits the varchar cap`() {
        // u:{uuid}:{uuid} = 2 + 36 + 1 + 36 = 75 ≤ varchar(80)
        assertThat(ConferenceIdentity.member(ALICE).connectionIdentity(CONN_ID)).hasSize(75)
        assertThat(ConferenceIdentity.MAX_LENGTH).isGreaterThanOrEqualTo(75)
    }

    companion object {
        private val ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001")
        private val BOB = UUID.fromString("00000000-0000-0000-0000-000000000002")
        private val CONN_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa")
    }
}
