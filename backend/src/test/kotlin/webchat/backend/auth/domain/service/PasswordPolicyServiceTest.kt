package webchat.backend.auth.domain.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.Resource
import webchat.backend.config.AuthPasswordProperties

/**
 * Unit-level mirror of the FR-004 cases mandated for T013b: <8 / >128 chars,
 * empty / whitespace-only, top-list `password123` from the bundled blocklist,
 * equality with username/email — plus blocklist loading and normalization.
 * The end-to-end contract checks live in RegistrationFlowIT.
 */
class PasswordPolicyServiceTest {
    private val policy =
        PasswordPolicyService(
            AuthPasswordProperties(ClassPathResource("auth/password-blocklist.txt")),
        )

    @Test
    fun `accepts policy compliant password`() {
        assertThat(policy.validate("C0rrect-Horse!42", "dave", "dave@example.com")).isNull()
    }

    @Test
    fun `accepts boundary lengths of exactly 8 and 128 characters`() {
        assertThat(policy.validate("Eight8!x", "dave", "dave@example.com")).isNull()
        assertThat(policy.validate("x".repeat(128), "dave", "dave@example.com")).isNull()
    }

    @Test
    fun `rejects passwords shorter than 8 characters`() {
        assertThat(policy.validate("Sh0rt!1", "dave", "dave@example.com"))
            .isEqualTo(PasswordPolicyViolation.TOO_SHORT)
    }

    @Test
    fun `rejects passwords longer than 128 characters`() {
        assertThat(policy.validate("L".repeat(129), "dave", "dave@example.com"))
            .isEqualTo(PasswordPolicyViolation.TOO_LONG)
    }

    @Test
    fun `rejects empty and whitespace-only passwords`() {
        assertThat(policy.validate("", "dave", "dave@example.com"))
            .isEqualTo(PasswordPolicyViolation.BLANK)
        assertThat(policy.validate("        ", "dave", "dave@example.com"))
            .isEqualTo(PasswordPolicyViolation.BLANK)
    }

    @Test
    fun `rejects top-list password from bundled blocklist`() {
        assertThat(policy.validate("password123", "dave", "dave@example.com"))
            .isEqualTo(PasswordPolicyViolation.TOO_COMMON)
    }

    @Test
    fun `blocklist comparison normalizes case and surrounding whitespace`() {
        assertThat(policy.validate("  Password123  ", "dave", "dave@example.com"))
            .isEqualTo(PasswordPolicyViolation.TOO_COMMON)
    }

    @Test
    fun `rejects password equal to username or email case-insensitively`() {
        assertThat(policy.validate("EdwardUser", "edwarduser", "edward@example.com"))
            .isEqualTo(PasswordPolicyViolation.SAME_AS_USERNAME)
        assertThat(policy.validate("Edward@Example.com", "edwarduser", "edward@example.com"))
            .isEqualTo(PasswordPolicyViolation.SAME_AS_EMAIL)
    }

    @Test
    fun `bundled blocklist loads from classpath with deterministic top entry`() {
        val blocklist = loadBlocklist()

        assertThat(blocklist).contains("password123")
        assertThat(blocklist.size).isGreaterThan(9000)
    }

    private fun loadBlocklist(): Set<String> {
        val resource: Resource = ClassPathResource("auth/password-blocklist.txt")
        return resource
            .inputStream
            .use { input ->
                input
                    .bufferedReader(Charsets.UTF_8)
                    .readLines()
                    .mapNotNull { line -> line.trim().lowercase().takeIf { it.isNotEmpty() } }
                    .toHashSet()
            }
    }
}
