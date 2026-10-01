package webchat.backend.presence

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import webchat.backend.AbstractIntegrationTest
import webchat.backend.chats.repository.JdbcChatRepository
import webchat.backend.chats.repository.JdbcParticipantRepository
import webchat.backend.contacts.domain.port.BlockRepository
import webchat.backend.contacts.domain.port.ContactRepository
import webchat.backend.groups.domain.model.GroupTitle
import webchat.backend.groups.domain.port.GroupRepository
import webchat.backend.presence.repository.JdbcVisibilityAudienceReader
import java.util.UUID

/**
 * T009 (tasks.md Phase 2, constitution VI) — the THIN ADAPTER SLICE of the
 * visibility policy: real Testcontainers PG 17 through
 * [AbstractIntegrationTest], no mocks, every method staging its own users
 * (the T012a/T010 discipline — the writable legs go through the EXISTING
 * ports of 004/006: ensure/create/removeMember/add/block; raw SQL only for
 * the `users` rows no port can write).
 *
 * The IT PINS the §1.4 membership formula (data-model 007; research.md
 * §C1) that BOTH presence legs — the `presence.updated` fan-out (T017)
 * and the №36 batch snapshot (T016) — resolve through, so the two can
 * never disagree about who may see whom (FR-003/FR-007, SC-004):
 *
 *  * the chat leg: ACTIVE co-participants of the subject's ACTIVE chats,
 *    direct AND group — a REMOVED group member (state='removed') is out
 *    (V14), a direct mate is in forever (V14 keeps direct rows active);
 *  * the contact leg is ONE-SIDED: only OWNERS having the subject in
 *    their contacts — the subject's own contact list grants nothing;
 *  * the block anti-join works in BOTH directions regardless of which
 *    side blocked (004 FR-020, research.md §D1);
 *  * the self and the empty/unknown/nonexistent answers stay inside the
 *    single `unknown` bucket — «no access» is INDISTINGUISHABLE from
 *    «does not exist» (FR-007, US4 AC4 — the HTTP-level T030 rides on
 *    this adapter contract).
 */
@Suppress("TooManyFunctions") // one method per formula rule of §1.4
class JdbcVisibilityAudienceReaderIT : AbstractIntegrationTest() {
    @Autowired
    private lateinit var audienceReader: JdbcVisibilityAudienceReader

    @Autowired
    private lateinit var chatRepository: JdbcChatRepository

    @Autowired
    private lateinit var participantRepository: JdbcParticipantRepository

    @Autowired
    private lateinit var groupRepository: GroupRepository

    @Autowired
    private lateinit var contactRepository: ContactRepository

    @Autowired
    private lateinit var blockRepository: BlockRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `audienceOf unions active chat mates and contact owners, minus block-pairs and self`() {
        val subject = newUser()
        val directMate = newUser() // a direct dialog — active forever (V14)
        val groupOwner = newUser() // the subject sits in the owner's group
        val groupMate = newUser()
        val contactOwner = newUser() // has the subject in contacts, NO shared chat
        val removedMate = newUser() // kicked from the shared group → state='removed'
        val exGroupOwner = newUser() // still an ACTIVE mate: the kick hit only removedMate
        val blockedByMe = newUser() // the subject blocked them (their contact of the subject dies too)
        val blockedMe = newUser() // they blocked the subject despite a shared chat
        val ownContact = newUser() // the SUBJECT'S contact — grants the OWNER nothing
        val stranger = newUser()

        chatRepository.ensure(subject, directMate)
        groupRepository.create(GroupTitle.normalize("Аудитория"), null, groupOwner, listOf(subject, groupMate))
        val exGroup =
            groupRepository.create(
                GroupTitle.normalize("Исключение"),
                null,
                exGroupOwner,
                listOf(subject, removedMate),
            )
        assertThat(participantRepository.removeMember(exGroup.id, removedMate))
            .overridingErrorMessage("staging: the kick must resolve to exactly one removed row")
            .isTrue
        contactRepository.add(contactOwner, subject)
        contactRepository.add(blockedByMe, subject) // contacts persist, the block still wins
        chatRepository.ensure(subject, blockedMe)
        contactRepository.add(subject, ownContact) // ONE-SIDED leg: wrong direction
        blockRepository.block(subject, blockedByMe)
        blockRepository.block(blockedMe, subject)

        val audience = audienceReader.audienceOf(subject)

        assertThat(audience)
            .overridingErrorMessage(
                "audience = active mates ∪ contact owners − block-pairs − self, got: %s",
                audience,
            ).containsExactlyInAnyOrder(directMate, groupOwner, groupMate, contactOwner, exGroupOwner)
        assertThat(audience)
            .doesNotContain(removedMate, blockedByMe, blockedMe, ownContact, stranger, subject)
    }

    @Test
    fun `audienceOf emits a direct-and-group mate exactly once and is empty for an unknown subject`() {
        val subject = newUser()
        val mate = newUser()
        chatRepository.ensure(subject, mate)
        groupRepository.create(GroupTitle.normalize("Дубль"), null, newUser(), listOf(subject, mate))

        val audience = audienceReader.audienceOf(subject)

        assertThat(audience).containsOnlyOnce(mate) // ONE rt:user:{mate} publication (T030 edge)
        assertThat(audienceReader.audienceOf(UUID.randomUUID()))
            .overridingErrorMessage("an unknown subject has no addressees — the fan-out is simply empty")
            .isEmpty()
    }

    @Test
    fun `visibleTargets mirrors audienceOf pairwise and keeps no-access indistinguishable`() {
        val observer = newUser()
        val staged = stageObserverNeighborhood(observer)

        val targets =
            listOf(
                staged.directMate,
                staged.contactOnly, // the observer's contact, no chat at all
                staged.groupMate,
                staged.groupHost, // the shared group's owner — an ACTIVE mate like any other
                staged.removedMate,
                staged.blockedByObserver, // the observer blocked them (their contact dies)
                staged.blockedObserver, // they blocked the observer despite a chat
                staged.stranger,
                UUID.randomUUID(), // nonexistent — same bucket as «no access»
                observer, // self — harmless
                staged.directMate, // duplicate — harmless
            )

        val visible = audienceReader.visibleTargets(observer, targets)

        assertThat(visible)
            .overridingErrorMessage(
                "the №36 policy keeps chat mates ∪ own contacts minus block-pairs, got: %s",
                visible,
            ).containsExactlyInAnyOrder(staged.directMate, staged.contactOnly, staged.groupMate, staged.groupHost)
        assertThat(visible).doesNotContain(observer) // set semantics absorb the duplicate and the self

        // The pairwise batch and the fan-out set are the SAME formula —
        // an event and a snapshot can never disagree (SC-004).
        assertMirrorsAudienceOf(observer, targets, visible)
    }

    @Test
    fun `visibleTargets answers an empty batch with no query at all`() {
        assertThat(audienceReader.visibleTargets(UUID.randomUUID(), emptyList())).isEmpty()
        assertThat(audienceReader.visibleTargets(UUID.randomUUID(), listOf())).isEmpty()
    }

    /** The №36 call shape: every membership leg plus every exclusion, staged through the 004/006 ports. */
    private data class Neighborhood(
        val directMate: UUID,
        val contactOnly: UUID,
        val groupMate: UUID,
        val groupHost: UUID,
        val removedMate: UUID,
        val blockedByObserver: UUID,
        val blockedObserver: UUID,
        val stranger: UUID,
    )

    private fun stageObserverNeighborhood(observer: UUID): Neighborhood {
        val directMate = newUser()
        val contactOnly = newUser()
        val groupMate = newUser()
        val groupHost = newUser()
        val removedMate = newUser()
        val blockedByObserver = newUser()
        val blockedObserver = newUser()
        val stranger = newUser()

        chatRepository.ensure(observer, directMate)
        contactRepository.add(observer, contactOnly)
        groupRepository.create(GroupTitle.normalize("Срез"), null, groupHost, listOf(observer, groupMate))
        val exGroup =
            groupRepository.create(
                GroupTitle.normalize("Ушёл"),
                null,
                newUser(), // an ACTIVE mate outside the requested batch — must not leak in
                listOf(observer, removedMate),
            )
        participantRepository.removeMember(exGroup.id, removedMate)
        contactRepository.add(blockedByObserver, observer)
        chatRepository.ensure(observer, blockedObserver)
        contactRepository.add(blockedByObserver, observer)
        blockRepository.block(observer, blockedByObserver)
        blockRepository.block(blockedObserver, observer)
        return Neighborhood(
            directMate = directMate,
            contactOnly = contactOnly,
            groupMate = groupMate,
            groupHost = groupHost,
            removedMate = removedMate,
            blockedByObserver = blockedByObserver,
            blockedObserver = blockedObserver,
            stranger = stranger,
        )
    }

    private fun assertMirrorsAudienceOf(
        observer: UUID,
        targets: List<UUID>,
        visible: Set<UUID>,
    ) {
        (targets - observer).toSet().forEach { target ->
            val expected = observer in audienceReader.audienceOf(target)
            assertThat(target in visible)
                .overridingErrorMessage(
                    "visibleTargets must equal audienceOf membership for %s (expected %s)",
                    target,
                    expected,
                ).isEqualTo(expected)
        }
    }

    // --- staging helpers (raw SQL only for state the ports cannot write) ---

    private fun newUser(): UUID {
        val id = UUID.randomUUID()
        val login = "t009-${id.toString().substring(0, 8)}"
        jdbcTemplate.update(INSERT_USER_SQL, id, login, "$login@example.com")
        return id
    }

    private companion object {
        val INSERT_USER_SQL =
            """
            INSERT INTO users (id, username, email, status)
            VALUES (?, ?, ?, 'pending_email_confirmation')
            """.trimIndent()
    }
}
