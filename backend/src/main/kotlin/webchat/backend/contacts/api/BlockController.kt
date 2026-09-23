package webchat.backend.contacts.api

import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import webchat.backend.contacts.domain.service.BlockService
import java.util.UUID

/**
 * The block lifecycle endpoints of User Story 5 (api-contract.md №23/№24,
 * FR-020) — thin HTTP adapters over [BlockService]: the `422
 * self_forbidden` pair guard and `404 user_not_found` of №23 stay in the
 * service; this layer only resolves the token owner (the blocker is the
 * caller — the token `sub` claim) and answers the idempotent `204` of
 * both legs. Every observable block effect (send refusals, read/badge
 * suppressions, `blockedByMe`) is derived from the relation elsewhere
 * (T054/T055) — nothing here touches the V10 tables or `user_contacts`.
 *
 * All failures leave as typed exceptions rendered problem+json by
 * [ContactsExceptionHandler].
 */
@RestController
@RequestMapping("/api/v1/users/{userId}/block")
class BlockController(
    private val blockService: BlockService,
) {
    /**
     * Contract №23: the idempotent block — `204` on both the first and
     * the repeated call (a repeat is observationally a no-op, edge spec).
     */
    @PutMapping
    fun block(
        @PathVariable userId: UUID,
        @AuthenticationPrincipal accessToken: Jwt,
    ): ResponseEntity<Unit> {
        blockService.block(callerId(accessToken), userId)
        return ResponseEntity.noContent().build()
    }

    /**
     * Contract №24: the unconditional idempotent unblock — `204` either
     * way (even for an unknown or not-blocked target); the ordinary
     * dialog behavior resumes immediately for both sides.
     */
    @DeleteMapping
    fun unblock(
        @PathVariable userId: UUID,
        @AuthenticationPrincipal accessToken: Jwt,
    ): ResponseEntity<Unit> {
        blockService.unblock(callerId(accessToken), userId)
        return ResponseEntity.noContent().build()
    }

    private fun callerId(accessToken: Jwt): UUID = UUID.fromString(accessToken.subject)
}
