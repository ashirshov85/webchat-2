package webchat.backend

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate

class ContextLoadsIT(
    @Autowired private val jdbcTemplate: JdbcTemplate,
) : AbstractIntegrationTest() {
    private val expectedTables =
        setOf(
            "users",
            "one_time_tokens",
            "sessions",
            "refresh_tokens",
            "email_outbox",
            "auth_events",
        )

    @Test
    fun contextLoadsWithPostgresAndRedis() {
    }

    @Test
    fun flywayAppliedAllTables() {
        val appliedTables =
            jdbcTemplate.queryForList(
                """
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public'
                """.trimIndent(),
                String::class.java,
            ).toSet()

        assertThat(appliedTables).containsAll(expectedTables)
    }
}
