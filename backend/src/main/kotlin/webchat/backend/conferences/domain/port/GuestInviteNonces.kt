package webchat.backend.conferences.domain.port

import webchat.backend.conferences.domain.model.GuestInvite

/**
 * The №52 nonce minting port (T045, DIP — plan.md VIII): the domain
 * [webchat.backend.conferences.domain.service.GuestInviteService] needs
 * the cryptographic half of a guest link, while the signer —
 * `webchat.backend.conferences.livekit.GuestInviteTokens` (T044: Nimbus
 * ES256 over the auth `Es256KeyRing`, `typ:"guest-invite"` separating
 * the nonce class from access tokens) — stays outside the domain like
 * every adapter. The [LiveKitTokenMinter] twin of the client-token
 * mint.
 *
 * [mint] is a pure signature computation — no I/O, no state: the same
 * [GuestInvite] row always serializes to the same nonce, so the mint
 * is safe to repeat and never fails on its own (a keyring breach is a
 * startup-time configuration failure, not a runtime answer).
 */
fun interface GuestInviteNonces {
    /**
     * Signs the nonce of [invite] — `sub = inviteId`, `cid =
     * conferenceId`, `typ = "guest-invite"`, `exp = expiresAt`
     * (T044): the `exp` claim and the status-row horizon are ONE value
     * by construction (data-model 009 §Сущность 3), so the link and
     * its row can never drift apart.
     */
    fun mint(invite: GuestInvite): String
}
