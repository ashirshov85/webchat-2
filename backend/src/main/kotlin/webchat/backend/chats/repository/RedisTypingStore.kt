package webchat.backend.chats.repository

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Repository
import webchat.backend.chats.domain.port.TypingState
import webchat.backend.chats.domain.port.TypingStore
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The Redis adapter of the [TypingStore] port (T028; data-model 008a
 * §2.1, research.md §A2/§A4): the WHOLE ephemeral typing state lives in
 * the shared Redis (SC-003 — zero trace in PG, constitution II — the
 * typer's №41 signal and the observer's №18 stream may ride different
 * pods) as exactly two key families:
 *
 *  - `typing:active:{chatId}` — ZSET, member=userId, score=expiresAtMs:
 *    the per-chat «who is typing» truth behind the start/stop legs;
 *  - `typing:watch` — the ONE global ZSET of the self-expiry poller
 *    (T032), member=`{chatId}:{userId}` (UUIDs carry no `:`, the pair
 *    splits unambiguously), score=the SAME expiresAtMs.
 *
 * Every leg is ONE atomic Lua script (the 007 `RedisPresenceStore`
 * discipline): a start/storm of competing pods serializes on the single
 * Redis command thread, and the due-expiry claim of the poller resolves
 * multi-replica competition by the ZREM itself — the loser of the race
 * finds nothing left and publishes nothing. All scripts take the clock
 * from Redis `TIME`, so the scores stay comparable regardless of any
 * pod's wall clock. Both keys additionally carry a Redis TTL as garbage
 * insurance (data-model §2.1 «ключ-строк TTL = таймаут + буфер»):
 * state-ttl + [KEY_TTL_BUFFER_MS], re-armed by every start — an idle
 * dialog's keys vanish entirely even if a poller outage let members
 * lapse unreaped, and the client's 10 s insurance timeout (§A2) covers
 * the observers long before the buffer matters.
 */
@Repository
class RedisTypingStore(
    private val redisTemplate: StringRedisTemplate,
) : TypingStore {
    /** №41 `start`: ZADD both ZSETs (a repeat extends) + the key-TTL re-arm (§2.1). */
    override fun start(
        chatId: UUID,
        userId: UUID,
        ttl: Duration,
    ) {
        evalLong(
            START_SCRIPT,
            listOf(activeKey(chatId), WATCH_KEY),
            ttl.toMillis().toString(),
            userId.toString(),
            watchMember(chatId, userId),
        )
    }

    /**
     * The extinguish leg: ZREM both; the `typing:active` removal count
     * IS the «an active state existed» answer the caller gates its
     * `typing.stopped` publication on (idempotence, port contract).
     */
    override fun stop(
        chatId: UUID,
        userId: UUID,
    ): Boolean =
        evalLong(
            STOP_SCRIPT,
            listOf(activeKey(chatId), WATCH_KEY),
            userId.toString(),
            watchMember(chatId, userId),
        ) == 1L

    /** The poller claim: the due `{chatId}:{userId}` pairs, removed from both ZSETs in the SAME script. */
    override fun dueExpired(batch: Int): List<TypingState> {
        val flat = evalList(CLAIM_SCRIPT, listOf(WATCH_KEY), batch.toString())
        return flat
            .chunked(FLAT_TRIPLE)
            .mapNotNull { triple ->
                val chatId = runCatching { UUID.fromString(triple.getOrElse(0) { null }?.toString()) }.getOrNull()
                val userId = runCatching { UUID.fromString(triple.getOrElse(1) { null }?.toString()) }.getOrNull()
                if (chatId == null || userId == null) {
                    null // a malformed member never reaches the CLAIM output; the guard is erasure hygiene only
                } else {
                    TypingState(
                        chatId = chatId,
                        userId = userId,
                        expiresAt = Instant.ofEpochMilli(number(triple.getOrElse(2) { null })),
                    )
                }
            }
    }

    private fun activeKey(chatId: UUID): String = "$ACTIVE_KEY_PREFIX$chatId"

    private fun watchMember(
        chatId: UUID,
        userId: UUID,
    ): String = "$chatId:$userId"

    private fun evalLong(
        script: DefaultRedisScript<Long>,
        keys: List<String>,
        vararg args: String,
    ): Long = redisTemplate.execute(script, keys, *args) ?: 0L

    private fun evalList(
        script: DefaultRedisScript<*>,
        keys: List<String>,
        vararg args: String,
    ): List<*> {
        @Suppress("UNCHECKED_CAST") // the CLAIM script always replies a flat list (erasure-safe)
        val typed = script as RedisScript<List<Any?>>
        return typed.let { redisTemplate.execute(it, keys, *args) }.orEmpty()
    }

    private fun number(value: Any?): Long =
        when (value) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull() ?: 0L
            else -> 0L
        }

    private companion object {
        /** data-model 008a §2.1 key families. */
        const val ACTIVE_KEY_PREFIX = "typing:active:"
        const val WATCH_KEY = "typing:watch"

        /**
         * The claim reply shape: {chatId, userId, expiresAtMs} triples
         * flattened into ONE Lua list.
         */
        const val FLAT_TRIPLE = 3

        /**
         * The key-TTL garbage-insurance buffer on top of state-ttl
         * (data-model §2.1): comfortably covers the poller drain slack
         * (1 s tick + the batch-cap back-pressure of a storm), so the
         * keys outlive every member they may still carry yet vanish
         * shortly after the dialog falls idle.
         */
        const val KEY_TTL_BUFFER_MS = 60_000L

        /** The server-clock preamble of every script: scores stay comparable across pods. */
        private const val CLOCK_LUA =
            """
            local clock = redis.call('TIME')
            local now = tonumber(clock[1]) * 1000 + math.floor(tonumber(clock[2]) / 1000)
            """

        // KEYS: active, watch; ARGV: ttlMs, userId, watchMember → 1 (a repeat ZADD overwrites the score — extends)
        private val START_SCRIPT: DefaultRedisScript<Long> =
            DefaultRedisScript(
                """
                $CLOCK_LUA
                local expiry = now + tonumber(ARGV[1])
                redis.call('ZADD', KEYS[1], expiry, ARGV[2])
                redis.call('ZADD', KEYS[2], expiry, ARGV[3])
                redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[1]) + $KEY_TTL_BUFFER_MS)
                redis.call('PEXPIRE', KEYS[2], tonumber(ARGV[1]) + $KEY_TTL_BUFFER_MS)
                return 1
                """.trimIndent(),
                Long::class.java,
            )

        // KEYS: active, watch; ARGV: userId, watchMember → 1|0 (the ACTIVE removal count is the claim)
        private val STOP_SCRIPT: DefaultRedisScript<Long> =
            DefaultRedisScript(
                """
                local removed = redis.call('ZREM', KEYS[1], ARGV[1])
                redis.call('ZREM', KEYS[2], ARGV[2])
                return removed
                """.trimIndent(),
                Long::class.java,
            )

        // KEYS: watch; ARGV: batch → {chatId, userId, expiresAtMs, …} — the atomic due-expiry CLAIM:
        // the due members are read AND removed from both ZSETs inside the ONE script, so a competing
        // replica (or a racing №41 stop/№16 extinguish) finds nothing left — the ZREM is the claim and
        // the publication behind it stays at-most-once. A malformed member is dropped from the trigger
        // but never reported (its derived active key is unknowable).
        private val CLAIM_SCRIPT: DefaultRedisScript<List<*>> =
            DefaultRedisScript(
                """
                $CLOCK_LUA
                local due = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', now, 'LIMIT', 0, tonumber(ARGV[1]))
                local out = {}
                for _, member in ipairs(due) do
                  local score = tonumber(redis.call('ZSCORE', KEYS[1], member)) or now
                  redis.call('ZREM', KEYS[1], member)
                  local chatId, userId = string.match(member, '^([^:]+):([^:]+)$')
                  if chatId and userId then
                    redis.call('ZREM', '$ACTIVE_KEY_PREFIX' .. chatId, userId)
                    out[#out + 1] = chatId
                    out[#out + 1] = userId
                    out[#out + 1] = score
                  end
                end
                return out
                """.trimIndent(),
                List::class.java,
            )
    }
}
