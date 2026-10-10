package webchat.backend.presence.domain.port

import java.util.UUID

/**
 * T032 (tasks.md 007, US4): the durable home of the «невидимка» mode —
 * the V15 `users.presence_hidden` column (data-model 007 §1.5/§2, PG —
 * the ONLY durable state of the feature; everything else lives in
 * Redis). The domain resolves the mode through this port (DIP: the SQL
 * adapter `webchat.backend.presence.repository.JdbcPresenceSettingsStore`
 * stays outside), so [webchat.backend.presence.domain.PresenceService]
 * never touches JDBC and the №38 route
 * (`webchat.backend.presence.api.PresenceSettingsController`) stays a
 * thin HTTP projection.
 *
 * The mode acts ON THE WHOLE USER, never on a device (US4 AC5): the
 * row is keyed by the user id, so a fresh 002 session of the same
 * account reads the very value the previous session left (FR-007 — the
 * relogin edge of T031). The mapping is fixed by contracts/
 * presence-api.md §3: «невидимка» (spec/UI) ≡ `incognito` (№38 wire
 * field) ≡ `presence_hidden` (the V15 column).
 */
interface PresenceSettingsStore {
    /**
     * №38 GET (T032): the persisted mode of [userId]. The V15 column is
     * `BOOLEAN NOT NULL DEFAULT FALSE`, so every existing user always
     * answers a definite value — `false` unless a №38 PUT flipped it.
     */
    fun incognitoOf(userId: UUID): Boolean

    /**
     * T044 (tasks.md 008a, US3; research 008a §B2): the BATCH read of the
     * same V15 mode over the №36 snapshot's candidate targets — ONE
     * `SELECT … WHERE id IN (…)` per snapshot (≤ 200 after the API-layer
     * dedup, so the IN-list stays bounded), the incognito half of the
     * lastSeen disclosure conjunction. A userId absent from the answer
     * simply has no row — the caller only consults this leg for targets
     * the visibility policy already deemed visible (a nonexistent or
     * outsider target resolved to `unknown` long before), so a missing
     * entry may safely default to «not hidden».
     */
    fun incognitoBatch(userIds: Collection<UUID>): Map<UUID, Boolean>

    /**
     * №38 PUT (T032): persist [incognito] ATOMICALLY-IF-CHANGED — one
     * conditional `UPDATE … WHERE presence_hidden <> ?` whose affected
     * row count IS the change verdict, so a concurrent repeat of the
     * very same value (two tabs racing, a retried request) resolves as
     * the IDEMPOTENT no-op of presence-api.md §3 exactly once: `false`
     * means nothing was written, no event may follow and no rev may
     * advance — the caller (PresenceService) decides the transition
     * legs on this verdict alone.
     */
    fun storeIfChanged(
        userId: UUID,
        incognito: Boolean,
    ): Boolean
}
