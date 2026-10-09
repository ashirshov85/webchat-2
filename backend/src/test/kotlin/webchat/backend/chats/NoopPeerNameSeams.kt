package webchat.backend.chats

import webchat.backend.contacts.domain.model.Contact
import webchat.backend.contacts.domain.model.ContactAlias
import webchat.backend.contacts.domain.model.ContactSort
import webchat.backend.contacts.domain.port.ContactAddResult
import webchat.backend.contacts.domain.port.ContactEntry
import webchat.backend.contacts.domain.port.ContactRepository
import webchat.backend.users.domain.model.DisplayName
import webchat.backend.users.domain.model.Profile
import webchat.backend.users.domain.port.ProfileStore
import java.util.UUID

/**
 * Inert seams of the 008a peer-name join (T015): [ChatService][webchat.backend.chats.domain.service.ChatService]
 * takes a [ProfileStore] and a [ContactRepository] for the optional
 * `displayName`/`peerAlias` slots of the №11/№12/№13 peer fragments —
 * the pre-008a unit suites of the №11–№25 surface never exercise the
 * name legs, so they wire these no-ops instead (the real coverage of
 * the join is the integration pair ProfileDisplayNameIT/ContactAliasIT
 * of T009/T010, green at the T026 checkpoint).
 */
internal object NoopProfileStore : ProfileStore {
    override fun updateDisplayName(
        userId: UUID,
        displayName: DisplayName?,
    ): Profile = error("the profile write leg is not part of these suites")

    override fun findByUserId(userId: UUID): Profile? = null
}

/** Same scope as [NoopProfileStore]: every read answers «no aliases», no write is expected. */
internal object NoopContactRepository : ContactRepository {
    override fun add(
        ownerId: UUID,
        contactUserId: UUID,
    ): ContactAddResult = error("the contact write legs are not part of these suites")

    override fun remove(
        ownerId: UUID,
        contactUserId: UUID,
    ) {
        // inert by design — no suite ever removes a contact through this seam
    }

    override fun listByOwner(
        ownerId: UUID,
        sort: ContactSort,
    ): List<ContactEntry> = emptyList()

    override fun storeAlias(
        ownerId: UUID,
        contactUserId: UUID,
        alias: ContactAlias?,
    ): Contact? = null

    override fun aliasesOf(
        ownerId: UUID,
        userIds: Collection<UUID>,
    ): Map<UUID, String> = emptyMap()
}
