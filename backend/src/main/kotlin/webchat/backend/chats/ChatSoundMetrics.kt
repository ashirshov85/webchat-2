package webchat.backend.chats

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

/**
 * The verdict of one №42 attempt against the
 * `webchat_sound_settings_updated_total` vocabulary — serialized as the
 * lower-case `result` tag value of realtime-events.md 008a §4.
 */
enum class SoundUpdateResult {
    /** A genuine change: the stored value flipped, the write landed, the own-channel frame went out. */
    CHANGED,

    /** The idempotent no-op: the stored value already equals the submitted one — `200` WITHOUT an event. */
    UNCHANGED,

    /** The flood refusal: the `rl:user:chat-sound:` bucket rejected the toggle (429 `flood_limit`). */
    REJECTED,
}

/**
 * T053 — the FR-017 observability vocabulary of
 * contracts/realtime-events.md 008a §4, on the shared Micrometer stack of
 * 001 — no new dependencies:
 *
 *  * counter `webchat_sound_settings_updated_total{result}` — one
 *    increment per №42 `PUT /chats/{chatId}/sound` attempt, tagged by the
 *    verdict: [SoundUpdateResult.CHANGED] (a genuine flip — the durable
 *    write plus the own-channel `chat.sound.updated` frame, wired by
 *    [ChatService.updateSound][webchat.backend.chats.domain.service.ChatService.updateSound]),
 *    [SoundUpdateResult.UNCHANGED] (the idempotent repeat of the stored
 *    value — `200` without an event, wired by the same service leg) and
 *    [SoundUpdateResult.REJECTED] (the per-user flood bucket refused the
 *    toggle — `429 flood_limit`, NOTHING written and NOTHING published,
 *    wired by the api layer of T053 exactly like the typing
 *    flood-suppression counter).
 *
 * The name carries the Prometheus suffix verbatim (`_total` is
 * idempotent for counters), so `/actuator/prometheus` renders exactly the
 * series realtime-events.md §4 and T062 assert.
 *
 * Constitution V: the single `result` tag carries the verdict vocabulary
 * only — never chat or user identifiers.
 */
@Component
class ChatSoundMetrics(
    private val meterRegistry: MeterRegistry,
) {
    /**
     * Counts one №42 attempt by its [verdict] — the `result` leg of the
     * §4 vocabulary; the counter may only grow.
     */
    fun countUpdate(verdict: SoundUpdateResult) {
        updatedTotal(verdict).increment()
    }

    private fun updatedTotal(verdict: SoundUpdateResult): Counter =
        Counter
            .builder(SOUND_SETTINGS_UPDATED_TOTAL)
            .tag(TAG_RESULT, verdict.name.lowercase())
            .description(
                "Chat sound toggle (№42) attempts by verdict: changed (write + own-channel frame), " +
                    "unchanged (idempotent repeat, no event), rejected (flood limit, FR-012/FR-015)",
            ).register(meterRegistry)

    private companion object {
        // realtime-events.md 008a §4 observability contract name (FR-017)
        const val SOUND_SETTINGS_UPDATED_TOTAL = "webchat_sound_settings_updated_total"

        const val TAG_RESULT = "result"
    }
}
