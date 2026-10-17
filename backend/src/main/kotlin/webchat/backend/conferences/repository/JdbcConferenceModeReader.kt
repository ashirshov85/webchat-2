package webchat.backend.conferences.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import webchat.backend.conferences.api.dto.ConferenceMode
import webchat.backend.conferences.domain.port.ConferenceModeReader
import java.util.UUID

/**
 * T019 — the DIP adapter of [ConferenceModeReader]: ONE cross-domain
 * SQL read of the `messages` history (the established monolith
 * convention — [ChatMembershipCheckerImpl] reads `chats` the same way):
 * the LATEST `conference_invite` widget of the conference within the
 * chat, projected to its `payload.mode`. The `chat_id` equality keeps
 * the scan on the per-chat `seq` scope; the `payload->>'conferenceId'`
 * predicate discriminates the widget (the history of a long-lived chat
 * may carry widgets of MANY conferences — every previous one ended).
 * An unknown label folds to `null` — the caller's broken-invariant
 * path, never a guess (constitution III).
 */
@Repository
class JdbcConferenceModeReader(
    private val jdbcTemplate: JdbcTemplate,
) : ConferenceModeReader {
    override fun modeOf(
        conferenceId: UUID,
        chatId: UUID,
    ): ConferenceMode? =
        jdbcTemplate
            .query(FIND_MODE_SQL, { rs, _ -> rs.getString(MODE_COLUMN) }, chatId, conferenceId.toString())
            .firstOrNull()
            ?.let(::modeOfLabel)

    private companion object {
        const val MODE_COLUMN = "mode"

        val FIND_MODE_SQL =
            """
            SELECT payload->>'mode' AS mode
            FROM messages
            WHERE chat_id = ?
              AND kind = 'system'
              AND payload->>'type' = 'conference_invite'
              AND payload->>'conferenceId' = ?
            ORDER BY seq DESC
            LIMIT 1
            """.trimIndent()

        /** The contract labels of `CreateConferenceRequest.mode`/`ConferenceView.mode` (openapi.yaml 009). */
        fun modeOfLabel(label: String): ConferenceMode? =
            when (label) {
                "video" -> ConferenceMode.VIDEO
                "audio" -> ConferenceMode.AUDIO
                else -> null
            }
    }
}
