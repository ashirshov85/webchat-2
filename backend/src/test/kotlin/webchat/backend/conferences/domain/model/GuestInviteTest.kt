package webchat.backend.conferences.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Unit-level mirror of the GuestInvite invariants mandated for T008
 * (data-model 009 §Сущность 3): the TTL horizon `expires_at =
 * created_at + conferences.guest-invite-ttl` (Q19=A), the V18 CHECK
 * shapes (`expires_at > created_at`, `revoked ⟺ revoked_at`), the
 * №54/№55 validity predicate and the idempotent revocation reused by
 * both №53 and the Q17=A pre-revocation of №52. The «one active»
 * transactional invariant is the IT of T048.
 */
class GuestInviteTest {
    @Test
    fun `issue sets the ttl horizon as expiry`() {
        val invite = GuestInvite.issue(INVITE_ID, CONFERENCE_ID, ALICE, CREATED_AT, TTL)

        assertThat(invite.status).isEqualTo(GuestInviteStatus.ACTIVE)
        assertThat(invite.expiresAt).isEqualTo(CREATED_AT.plus(TTL))
        assertThat(invite.revokedAt).isNull()
        assertThat(invite.isValid(CREATED_AT)).isTrue
        assertThat(invite.isValid(CREATED_AT.plusSeconds(TTL.toSeconds() - 1))).isTrue
    }

    @Test
    fun `validity lapses at the horizon`() {
        val invite = GuestInvite.issue(INVITE_ID, CONFERENCE_ID, ALICE, CREATED_AT, TTL)

        assertThat(invite.isValid(CREATED_AT.plus(TTL))).isFalse
        assertThat(invite.isValid(CREATED_AT.plus(Duration.ofDays(2)))).isFalse
    }

    @Test
    fun `revoke moves to revoked with the timestamp`() {
        val revoked = GuestInvite.issue(INVITE_ID, CONFERENCE_ID, ALICE, CREATED_AT, TTL).revoke(REVOKED_AT)

        assertThat(revoked.status).isEqualTo(GuestInviteStatus.REVOKED)
        assertThat(revoked.revokedAt).isEqualTo(REVOKED_AT)
        assertThat(revoked.isValid(REVOKED_AT)).isFalse
    }

    @Test
    fun `revoke is idempotent and keeps the original timestamp`() {
        val revoked = GuestInvite.issue(INVITE_ID, CONFERENCE_ID, ALICE, CREATED_AT, TTL).revoke(REVOKED_AT)

        assertThat(revoked.revoke(LATER)).isEqualTo(revoked)
        assertThat(revoked.revokedAt).isEqualTo(REVOKED_AT)
    }

    @Test
    fun `a non-positive ttl violates the CHECK`() {
        assertThrows<IllegalArgumentException> {
            GuestInvite.issue(INVITE_ID, CONFERENCE_ID, ALICE, CREATED_AT, Duration.ZERO)
        }
        assertThrows<IllegalArgumentException> {
            GuestInvite.issue(INVITE_ID, CONFERENCE_ID, ALICE, CREATED_AT, Duration.ofSeconds(-1))
        }
    }

    @Test
    fun `revoked without a timestamp violates the CHECK`() {
        assertThrows<IllegalArgumentException> {
            GuestInvite(
                id = INVITE_ID,
                conferenceId = CONFERENCE_ID,
                createdBy = ALICE,
                status = GuestInviteStatus.REVOKED,
                createdAt = CREATED_AT,
                expiresAt = CREATED_AT.plus(TTL),
                revokedAt = null,
            )
        }
    }

    companion object {
        private val INVITE_ID = UUID.fromString("00000000-0000-0000-0000-000000000099")
        private val CONFERENCE_ID = UUID.fromString("00000000-0000-0000-0000-00000000000f")
        private val ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001")
        private val CREATED_AT = Instant.parse("2026-10-16T10:00:00Z")
        private val REVOKED_AT = Instant.parse("2026-10-16T12:00:00Z")
        private val LATER = Instant.parse("2026-10-16T13:00:00Z")
        private val TTL = Duration.ofHours(24)
    }
}
