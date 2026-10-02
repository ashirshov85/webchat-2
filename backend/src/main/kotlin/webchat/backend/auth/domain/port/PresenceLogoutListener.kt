package webchat.backend.auth.domain.port

import java.util.UUID

/**
 * The presence seam of the №7 logout (tasks.md 007 T029; research 007 §B2,
 * data-model 007 §1.1/§1.2): 002 revokes exactly ONE session (a parallel
 * session of the same user is a different sid and stays untouched), so the
 * logout flow must hand the revoked (userId, sessionId) pair over to the
 * presence feature through this port — the registrations to clear are
 * exactly those whose `sessionId` ties them to the revoked session.
 *
 * The dependency direction follows plan.md 007 (Structure Decision) and the
 * T013 precedent of `webchat.backend.realtime.PresenceConnectionLifecycle`:
 * the integration lives in `auth/` and calls presence LATE-BOUND — Spring
 * wires whatever bean implements this interface (T014
 * `webchat.backend.presence.domain.PresenceService`), so the auth flow
 * itself stays presence-agnostic and keeps working before/without presence
 * exactly as it did through 002–006.
 *
 * Contract duties of an implementation:
 *  - the call must be idempotent: a repeat logout of an already dead
 *    session never reaches this port (the service only invokes it on the
 *    revocation that actually happened), but the implementation must stay
 *    a no-op for a session with no live registrations anyway (FR-003);
 *  - a failure must be signalled by THROWING — the caller converts it into
 *    a warn (the №7 revocation stands regardless; the registration
 *    self-expires through the TTL/offq machinery, FR-002/FR-004).
 */
interface PresenceLogoutListener {
    /**
     * The logout leg (research 007 §B2): clear every presence registration
     * carrying [sessionId]. Live registrations of OTHER sessions of
     * [userId] remain — the status stays «online» (edge
     * «мультидевайс-logout»); the removal of the LAST one publishes
     * `offline` IMMEDIATELY, bypassing the hysteresis queue (a revoked
     * session cannot return — the tokens are denylisted, the window is
     * pointless; FR-004's single logout exception).
     */
    fun onSessionLoggedOut(
        userId: UUID,
        sessionId: UUID,
    )
}
