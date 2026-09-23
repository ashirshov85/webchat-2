package webchat.backend.contacts.domain.service

import org.springframework.stereotype.Service
import webchat.backend.contacts.domain.model.UserBlock
import webchat.backend.contacts.domain.port.BlockRepository
import webchat.backend.contacts.domain.port.UserLookupPort
import java.util.UUID

/**
 * The block lifecycle of User Story 5 (T052): the FR-020 relation managed
 * by the blocker — blocks stay fully independent of contacts and dialogs
 * (FR-020): no method here touches `user_contacts` or the V10 tables.
 *
 * Block (api-contract.md №23): `422 self_forbidden` for oneself and `404
 * user_not_found` for an unknown target are decided HERE, before the
 * repository is touched — the storage itself is idempotent (`ON CONFLICT`
 * on the V11 composite PK), so a repeat PUT is observationally a no-op:
 * both the first and the repeated call resolve to the stored
 * [UserBlock] and map to `204` (edge spec). Every observable block effect
 * (send refusals in both directions, read/badge suppressions,
 * `blockedByMe`) is derived from this relation later (T054) — the service
 * here only establishes and removes it.
 *
 * Unblock (№24): the unconditional idempotent delete — a missing entry is
 * NOT an error (`204` either way, even for an unknown target); the
 * ordinary dialog behavior resumes immediately for both sides.
 */
@Service
class BlockService(
    private val userLookup: UserLookupPort,
    private val blockRepository: BlockRepository,
) {
    fun block(
        blockerId: UUID,
        blockedId: UUID,
    ): UserBlock {
        if (blockerId == blockedId) throw SelfForbiddenException()
        if (userLookup.findById(blockedId) == null) throw UserNotFoundException()
        return blockRepository.block(blockerId, blockedId)
    }

    fun unblock(
        blockerId: UUID,
        blockedId: UUID,
    ) {
        blockRepository.unblock(blockerId, blockedId)
    }
}
