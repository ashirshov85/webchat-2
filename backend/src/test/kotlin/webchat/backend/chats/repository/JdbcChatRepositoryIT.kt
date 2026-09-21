package webchat.backend.chats.repository

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import webchat.backend.AbstractIntegrationTest
import java.util.UUID

/**
 * Regression IT for the canonical pair resolve of T011: the low/high order
 * MUST follow PostgreSQL's UNSIGNED byte-wise uuid comparison (the V10
 * `ck_chats_low_lt_high` CHECK and the `ux_chats_user_pair` key), while
 * Java's `UUID.compareTo` is SIGNED on the two 64-bit halves. The two
 * orders disagree exactly when the most significant byte crosses 0x80 —
 * the deterministic pair below pins that boundary, which the random pairs
 * of JdbcMessageRepositoryIT hit statistically (~50% of dialogs → CHECK
 * violation, a latent 500 on `/chats/ensure`).
 */
class JdbcChatRepositoryIT : AbstractIntegrationTest() {
    @Autowired
    private lateinit var chatRepository: JdbcChatRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `ensure canonicalizes the pair by PG byte order, not Java signed uuid order`() {
        // Signed msb: 0xf0.. is NEGATIVE, 0x10.. is positive → Java orders
        // f0.. BEFORE 10..; PG memcmp orders 0x10 < 0xf0 — only the latter
        // satisfies the CHECK.
        val highMsb = UUID.fromString("f0000000-0000-4000-8000-00000000000a")
        val lowMsb = UUID.fromString("10000000-0000-4000-8000-00000000000b")
        newUser(highMsb)
        newUser(lowMsb)

        val forward = chatRepository.ensure(highMsb, lowMsb).chat
        val reverse = chatRepository.ensure(lowMsb, highMsb).chat

        assertThat(forward.userLowId)
            .overridingErrorMessage("user_low_id must be the PG-byte-order least of the pair")
            .isEqualTo(lowMsb)
        assertThat(forward.userHighId).isEqualTo(highMsb)
        assertThat(reverse.id)
            .overridingErrorMessage("both call orders resolve the SAME dialog (FR-001/FR-018)")
            .isEqualTo(forward.id)
    }

    private fun newUser(id: UUID) {
        val login = "t011r-${id.toString().substring(0, 8)}"
        jdbcTemplate.update(
            INSERT_USER_SQL,
            id,
            login,
            "$login@example.com",
        )
    }

    private companion object {
        val INSERT_USER_SQL =
            """
            INSERT INTO users (id, username, email, status)
            VALUES (?, ?, ?, 'pending_email_confirmation')
            """.trimIndent()
    }
}
