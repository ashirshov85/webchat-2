package webchat.backend.conferences.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID

/**
 * Unit-level mirror of the Conference invariants mandated for T008
 * (data-model 009 §Сущность 1): forward-only status transitions with
 * idempotent no-ops (`created→active` on the first participant_joined,
 * finalize never rewriting `ended_at`/`end_reason` — invariant 5), the
 * directional CHECK shapes of V18, and the opaque room name
 * `c:{conferenceId}`. Conditional-UPDATE persistence is the IT of
 * T021+/T057.
 */
class ConferenceTest {
    @Test
    fun `create starts in created with no timestamps`() {
        val conference = Conference.create(ID, CHAT_ID, ALICE, MAX_PARTICIPANTS, CREATED_AT)

        assertThat(conference.status).isEqualTo(ConferenceStatus.CREATED)
        assertThat(conference.startedAt).isNull()
        assertThat(conference.endedAt).isNull()
        assertThat(conference.endReason).isNull()
        assertThat(conference.isEnded).isFalse
    }

    @Test
    fun `activate moves created to active with startedAt`() {
        val active = Conference.create(ID, CHAT_ID, ALICE, MAX_PARTICIPANTS, CREATED_AT).activate(JOINED_AT)

        assertThat(active).isNotNull
        assertThat(active!!.status).isEqualTo(ConferenceStatus.ACTIVE)
        assertThat(active.startedAt).isEqualTo(JOINED_AT)
        assertThat(active.endedAt).isNull()
    }

    @Test
    fun `duplicate activate is a no-op`() {
        val active = Conference.create(ID, CHAT_ID, ALICE, MAX_PARTICIPANTS, CREATED_AT).activate(JOINED_AT)

        assertThat(active!!.activate(LATER)).isNull()
    }

    @Test
    fun `activate after end is a no-op`() {
        val ended =
            Conference
                .create(ID, CHAT_ID, ALICE, MAX_PARTICIPANTS, CREATED_AT)
                .end(ConferenceEndReason.ROOM_LOST, LATER)

        assertThat(ended!!.activate(LATER)).isNull()
    }

    @Test
    fun `end from active records the reason`() {
        val ended =
            Conference
                .create(ID, CHAT_ID, ALICE, MAX_PARTICIPANTS, CREATED_AT)
                .activate(JOINED_AT)!!
                .end(ConferenceEndReason.LAST_LEFT, LATER)

        assertThat(ended).isNotNull
        assertThat(ended!!.status).isEqualTo(ConferenceStatus.ENDED)
        assertThat(ended.endedAt).isEqualTo(LATER)
        assertThat(ended.endReason).isEqualTo(ConferenceEndReason.LAST_LEFT)
        assertThat(ended.isEnded).isTrue
    }

    @Test
    fun `end from created keeps startedAt null — the room_lost shape`() {
        val ended =
            Conference
                .create(ID, CHAT_ID, ALICE, MAX_PARTICIPANTS, CREATED_AT)
                .end(ConferenceEndReason.ROOM_LOST, LATER)

        assertThat(ended).isNotNull
        assertThat(ended!!.status).isEqualTo(ConferenceStatus.ENDED)
        assertThat(ended.startedAt).isNull()
    }

    @Test
    fun `duplicate end never rewrites ended_at or end_reason — finalize idempotency`() {
        val ended =
            Conference
                .create(ID, CHAT_ID, ALICE, MAX_PARTICIPANTS, CREATED_AT)
                .activate(JOINED_AT)!!
                .end(ConferenceEndReason.MODERATOR, LATER)!!

        // a racing room_finished webhook must not move the pre-recorded reason
        assertThat(ended.end(ConferenceEndReason.LAST_LEFT, EVEN_LATER)).isNull()
        assertThat(ended.endedAt).isEqualTo(LATER)
        assertThat(ended.endReason).isEqualTo(ConferenceEndReason.MODERATOR)
    }

    @Test
    fun `every end reason maps to the enum`() {
        val reasons =
            listOf(
                ConferenceEndReason.LAST_LEFT,
                ConferenceEndReason.MODERATOR,
                ConferenceEndReason.MAX_DURATION,
                ConferenceEndReason.ROOM_LOST,
                ConferenceEndReason.CHAT_DELETED,
            )

        reasons.forEach { reason ->
            val ended = Conference.create(ID, CHAT_ID, ALICE, MAX_PARTICIPANTS, CREATED_AT).end(reason, LATER)
            assertThat(ended!!.endReason).isEqualTo(reason)
        }
    }

    @Test
    fun `room name is the opaque c-prefix without PII`() {
        val conference = Conference.create(ID, CHAT_ID, ALICE, MAX_PARTICIPANTS, CREATED_AT)

        assertThat(conference.roomName).isEqualTo("c:$ID")
    }

    @Test
    fun `rejects shapes violating the directional CHECKs`() {
        assertThrows<IllegalArgumentException> {
            Conference(
                ID,
                CHAT_ID,
                ALICE,
                ConferenceStatus.ENDED,
                MAX_PARTICIPANTS,
                CREATED_AT,
                endedAt = null,
                endReason = ConferenceEndReason.LAST_LEFT,
            )
        }
        assertThrows<IllegalArgumentException> {
            Conference(
                ID,
                CHAT_ID,
                ALICE,
                ConferenceStatus.ENDED,
                MAX_PARTICIPANTS,
                CREATED_AT,
                endedAt = LATER,
                endReason = null,
            )
        }
        assertThrows<IllegalArgumentException> {
            Conference(
                ID,
                CHAT_ID,
                ALICE,
                ConferenceStatus.CREATED,
                MAX_PARTICIPANTS,
                CREATED_AT,
                startedAt = JOINED_AT,
            )
        }
        assertThrows<IllegalArgumentException> {
            Conference(ID, CHAT_ID, ALICE, ConferenceStatus.ACTIVE, MAX_PARTICIPANTS, CREATED_AT, startedAt = null)
        }
        assertThrows<IllegalArgumentException> {
            Conference(ID, CHAT_ID, ALICE, ConferenceStatus.CREATED, 0, CREATED_AT)
        }
    }

    companion object {
        private val ID = UUID.fromString("00000000-0000-0000-0000-00000000000f")
        private val CHAT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1")
        private val ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001")
        private val CREATED_AT = Instant.parse("2026-10-16T10:00:00Z")
        private val JOINED_AT = Instant.parse("2026-10-16T10:00:05Z")
        private val LATER = Instant.parse("2026-10-16T10:30:00Z")
        private val EVEN_LATER = Instant.parse("2026-10-16T10:31:00Z")
        private const val MAX_PARTICIPANTS = 16
    }
}
