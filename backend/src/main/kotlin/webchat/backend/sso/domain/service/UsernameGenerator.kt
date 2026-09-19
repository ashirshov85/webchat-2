package webchat.backend.sso.domain.service

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import webchat.backend.auth.domain.port.UserRepository
import java.security.SecureRandom

/**
 * Raised when the candidate ladder is exhausted without a free username —
 * practically unreachable (the random tail alone offers millions of slots),
 * kept explicit so a pathological state can never loop silently.
 *
 * The message carries no submitted values (SC-005): the offending email is
 * already known to the caller that supplied it.
 */
class UsernameGenerationException : RuntimeException("no free username could be derived from the email local part")

/**
 * JIT username derivation and collision-free insertion (research.md §7;
 * data-model.md §2; tasks.md T028).
 *
 * Derivation: email local part → lowercase → keep `[a-z0-9._-]` → truncate
 * to 32 → enforce the 002 username pattern
 * `^[a-zA-Z0-9]([a-zA-Z0-9_.-]{1,30}[a-zA-Z0-9])$` (alphanumeric borders,
 * length ≥ 3) by trimming non-alphanumeric borders and prefixing `user` when
 * the result would be too short — the user never notices any of this (US2-1).
 *
 * Collisions (checked against the `lower(username)` unique index through
 * [UserRepository.findByUsername]): `-2..-99`, then a `-<4 random chars>`
 * tail so an attacker cannot pre-squat the whole deterministic ladder
 * (research.md §7 "Alternatives considered"). The generated value is always
 * lowercase and pattern-clean, hence can never collide with an email login
 * (the 002 pattern has no `@`).
 *
 * Insertion ([insertWithUniqueUsername]): the lookup above only narrows the
 * race — two concurrent JIT provisions of the same local part can still pass
 * it together, so each insert attempt runs in its own savepoint (NESTED
 * propagation) and a unique violation retries with the next candidate,
 * at most [MAX_INSERT_ATTEMPTS] times. The savepoint keeps the surrounding
 * JIT transaction of the caller (T029) alive across the failed attempts;
 * without an outer transaction the template simply commits each attempt
 * on its own.
 */
@Service
class UsernameGenerator(
    private val userRepository: UserRepository,
    transactionManager: PlatformTransactionManager,
) {
    /**
     * Savepoint template: a unique violation on `users` aborts only the
     * failed attempt (rolled back to the savepoint) while the caller's
     * transaction stays usable for the retry — a bare REQUIRED template
     * would poison the PostgreSQL transaction of the whole JIT provisioning.
     */
    private val attemptTx =
        TransactionTemplate(transactionManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_NESTED
        }

    private val random = SecureRandom()

    /**
     * The first candidate of the ladder that is neither among [excluding]
     * nor taken per the `lower(username)` unique index. [excluding] is how
     * [insertWithUniqueUsername] advances past candidates lost to a
     * concurrent insert after the savepoint rollback (they still LOOK free
     * to the pre-check).
     */
    @Suppress("ReturnCount") // the candidate ladder IS the contract: base, -2..-99, random tail
    fun generate(
        email: String,
        excluding: Set<String> = emptySet(),
    ): String {
        val base = baseFrom(email)
        if (base !in excluding && isFree(base)) return base

        for (suffix in FIRST_NUMERIC_SUFFIX..LAST_NUMERIC_SUFFIX) {
            val candidate = suffixed(base, suffix.toString())
            if (candidate !in excluding && isFree(candidate)) return candidate
        }

        repeat(RANDOM_ATTEMPTS) {
            val candidate = suffixed(base, randomTail())
            if (candidate !in excluding && isFree(candidate)) return candidate
        }

        throw UsernameGenerationException()
    }

    /**
     * Runs [insertAttempt] under the generated username, retrying on unique
     * violation with the next ladder
     * candidate (≤ [MAX_INSERT_ATTEMPTS] attempts, research.md §7). The
     * callback receives the chosen username and everything it writes shares
     * the attempt's savepoint — a collision rolls the whole failed attempt
     * back, never half of it. The last violation is rethrown after the
     * budget is spent so the caller keeps the root cause (e.g. a concurrent
     * JIT won the same `lower(email)` — a case a username change cannot fix).
     */
    fun <T> insertWithUniqueUsername(
        email: String,
        insertAttempt: (username: String) -> T,
    ): T {
        val attempted = mutableSetOf<String>()
        var lastViolation: DataIntegrityViolationException? = null
        repeat(MAX_INSERT_ATTEMPTS) {
            val username = generate(email, excluding = attempted)
            attempted += username
            try {
                return requireNotNull(attemptTx.execute { insertAttempt(username) }) {
                    "the insert attempt must return its outcome"
                }
            } catch (violation: DataIntegrityViolationException) {
                // a concurrent JIT won the insert race on lower(username) —
                // the next attempt advances past the taken candidate
                lastViolation = violation
            }
        }
        throw lastViolation ?: UsernameGenerationException()
    }

    /**
     * research.md §7 derivation: local part → lowercase → `[a-z0-9._-]` →
     * ≤ 32 → trim non-alphanumeric borders (pattern borders are alnum; a
     * truncation may expose `.`/`-`) → prefix `user` when still shorter
     * than the pattern minimum (the prefix keeps the result pattern-clean:
     * `user` + a border-trimmed tail of at most [MIN_LENGTH] - 1 chars).
     */
    private fun baseFrom(email: String): String {
        val localPart = email.substringBefore(EMAIL_SEPARATOR).lowercase()
        val filtered = localPart.filter { it in ALLOWED_CHARS }.take(MAX_LENGTH)
        val candidate = filtered.trim { it !in ALPHANUMERIC }
        return if (candidate.length >= MIN_LENGTH) candidate else USER_PREFIX + candidate
    }

    /**
     * `base-<suffix>` shortened to fit [MAX_LENGTH]: the stem is re-trimmed
     * after truncation, and the trailing suffix restores the alnum border,
     * so every suffixed candidate matches the 002 pattern by construction.
     */
    private fun suffixed(
        base: String,
        suffix: String,
    ): String {
        val stem = base.take(MAX_LENGTH - suffix.length - DASH.length).trim { it !in ALPHANUMERIC }
        return "$stem$DASH$suffix"
    }

    private fun randomTail(): String =
        buildString {
            repeat(RANDOM_TAIL_LENGTH) { append(ALPHANUMERIC[random.nextInt(ALPHANUMERIC.length)]) }
        }

    /** Collision check through the port — `lower(username) = ?` (research.md §7). */
    private fun isFree(candidate: String): Boolean = userRepository.findByUsername(candidate) == null

    private companion object {
        /** RegisterRequest.username pattern max length (api-contract.md §4, 002). */
        const val MAX_LENGTH = 32

        /** Pattern minimum: `^[a-zA-Z0-9](...)[a-zA-Z0-9]$` — 3 chars. */
        const val MIN_LENGTH = 3

        /** research.md §7: `user` prefix when the local part cannot satisfy the pattern. */
        const val USER_PREFIX = "user"

        const val FIRST_NUMERIC_SUFFIX = 2

        const val LAST_NUMERIC_SUFFIX = 99

        /** research.md §7: `-<4 random chars>` after the numeric ladder. */
        const val RANDOM_TAIL_LENGTH = 4

        /** Random-tail attempts before giving up — 36^4 slots make this a safety valve. */
        const val RANDOM_ATTEMPTS = 10

        /** research.md §7: insert retry budget on unique violation. */
        const val MAX_INSERT_ATTEMPTS = 5

        const val EMAIL_SEPARATOR = '@'

        const val DASH = "-"

        /** Post-lowercase filter charset of research.md §7. */
        const val ALLOWED_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789._-"

        const val ALPHANUMERIC = "abcdefghijklmnopqrstuvwxyz0123456789"
    }
}
