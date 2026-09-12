package webchat.backend

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate

class ContextLoadsIT(
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val redisTemplate: StringRedisTemplate,
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
        assertThat(jdbcTemplate.queryForObject("SELECT 1", Int::class.java)).isEqualTo(1)

        redisTemplate.opsForValue().set("context-it:ping", "pong")
        assertThat(redisTemplate.opsForValue().get("context-it:ping")).isEqualTo("pong")
    }

    @Test
    fun flywayAppliedAllTables() {
        val appliedTables =
            jdbcTemplate
                .queryForList(
                    """
                    SELECT table_name FROM information_schema.tables
                    WHERE table_schema = 'public'
                    """.trimIndent(),
                    String::class.java,
                ).toSet()

        assertThat(appliedTables).containsAll(expectedTables)
    }
}
