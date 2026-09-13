package webchat.backend.auth.domain.service

import org.springframework.core.io.Resource
import org.springframework.stereotype.Service
import webchat.backend.config.AuthPasswordProperties

/** Single violated FR-004 rule for a candidate password. */
enum class PasswordPolicyViolation {
    BLANK,
    TOO_SHORT,
    TOO_LONG,
    TOO_COMMON,
    SAME_AS_USERNAME,
    SAME_AS_EMAIL,
}

/**
 * Password policy FR-004 (research.md §3): length 8–128, non-blank, outside the
 * bundled weak-password blocklist and different from the account's username and
 * email.
 *
 * The blocklist (top-10k SecLists xato, pinned lowercase in the repository) is
 * loaded into memory once at startup from the mandatory `auth.password.blocklist-path`
 * key (fail-fast startup; [AuthPasswordProperties]). All comparisons use the
 * `trim().toLowerCase()` normalization. Open passwords are never logged (SC-005).
 */
@Service
class PasswordPolicyService(
    properties: AuthPasswordProperties,
) {
    private val blocklist: Set<String> = loadBlocklist(properties.blocklistPath)

    /**
     * Returns the first violated rule or `null` when the candidate complies with
     * the whole policy; checked before any token is consumed (T013b).
     */
    fun validate(
        password: String,
        username: String,
        email: String,
    ): PasswordPolicyViolation? =
        when {
            password.isBlank() -> PasswordPolicyViolation.BLANK
            password.length < MIN_LENGTH -> PasswordPolicyViolation.TOO_SHORT
            password.length > MAX_LENGTH -> PasswordPolicyViolation.TOO_LONG
            else -> normalizedViolation(password, username, email)
        }

    private fun normalizedViolation(
        password: String,
        username: String,
        email: String,
    ): PasswordPolicyViolation? {
        val normalized = normalize(password)
        return when {
            normalized in blocklist -> PasswordPolicyViolation.TOO_COMMON
            normalized == normalize(username) -> PasswordPolicyViolation.SAME_AS_USERNAME
            normalized == normalize(email) -> PasswordPolicyViolation.SAME_AS_EMAIL
            else -> null
        }
    }

    private fun loadBlocklist(resource: Resource): Set<String> =
        resource
            .inputStream
            .use { input ->
                input
                    .bufferedReader(Charsets.UTF_8)
                    .readLines()
                    .mapNotNull { line -> normalize(line).takeIf { it.isNotEmpty() } }
                    .toHashSet()
            }.also { loaded ->
                check(loaded.isNotEmpty()) { "password blocklist at $resource is empty" }
            }

    private fun normalize(value: String): String = value.trim().lowercase()

    private companion object {
        const val MIN_LENGTH = 8

        const val MAX_LENGTH = 128
    }
}
