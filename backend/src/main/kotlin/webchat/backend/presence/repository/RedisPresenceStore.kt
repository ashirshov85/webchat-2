package webchat.backend.presence.repository

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Repository
import webchat.backend.config.PresenceProperties
import webchat.backend.presence.domain.port.OfflineDueOutcome
import webchat.backend.presence.domain.port.PresencePublishedStatus
import webchat.backend.presence.domain.port.PresenceRegistration
import webchat.backend.presence.domain.port.PresenceStore
import webchat.backend.presence.domain.port.PresenceTransition
import webchat.backend.presence.domain.port.PublishedPresence
import java.time.Instant
import java.util.UUID

/**
 * The Redis adapter of the [PresenceStore] port (T008; data-model 007 §3,
 * research.md §A1/§A2/§B1/§B2): the WHOLE ephemeral presence state lives in
 * the shared Redis (constitution II — instances stay stateless, the SSE
 * connections of one user may ride different pods, FR-005) as the §3 key
 * families:
 *
 *  - `presence:alive:{userId}` — ZSET, member=connectionId, score=expiresAt;
 *    every read lazily evicts the expired members (ZREMRANGEBYSCORE
 *    −inf..now — FR-002: an expired registration never counts as alive);
 *  - `presence:watch` / `presence:offq` — the global trigger ZSETs of the
 *    silent-expiry watcher and the hysteresis queue;
 *  - `presence:pub:{userId}` / `presence:rev:{userId}` /
 *    `presence:online:count` — the published status (TTL 25 h, renewed by
 *    the heartbeat and every transition), the monotone revision (TTL 7 h,
 *    renewed on increment; a nil rev is epoch-initialized with the server
 *    `now_ms`, so revisions stay strictly increasing across «epochs» —
 *    research.md §C2) and the published-online counter (FR-009);
 *  - `presence:conn:{userId}` — the adapter-internal HASH
 *    connectionId→sessionId, the index [clearSessionRegistrations] needs
 *    to remove exactly the logout session's registrations (T029/research
 *    §B2); a TTL-bounded projection of `alive` that self-expires;
 *  - `presence:lastseen:{userId}` — STRING, the epoch-ms stamp of the
 *    last presence activity (008a US3, data-model 008a §2.2): the
 *    REGISTER/RENEW/UNREGISTER/CLEAR_SESSION legs refresh it atomically
 *    with their own mutation (Redis `TIME` — the same clock as the
 *    scores; TTL 30 d, older simply lapses into the client's «давно»
 *    fallback), the SNAPSHOT leg reads it batch-wise (T043) and the
 *    reap leg deliberately writes NOTHING on a silent expiry — the
 *    stamp of the last heartbeat stays, so «обрыв» drifts only within
 *    the TTL-model budget of SC-005 (research 008a §B1).
 *
 * Every state leg of the §1.2 machine is ONE atomic Lua script — the
 * CAS of `presence:pub` + the rev advance + the counter arithmetic + the
 * offq mutations execute indivisibly, so competing stateless instances
 * and pollers serialize safely and a repeat execution is always a no-op
 * (FR-003/FR-005; data-model §3 «Атомарность»). All scripts take the
 * clock from Redis `TIME` — the scores of one key family are always
 * comparable regardless of the pod's wall clock. The watch ZSET score is
 * max(expiresAt) of the user's registrations (research.md §A2): the
 * RENEW leg has raised it GT-style since T008, and T027 (US3) added the
 * register leg (a GT ZADD of the fresh expiry — it may only push the
 * deadline UP) and the unregister leg (a recompute to the MAX of the
 * REMAINING registrations, ZREM when none are left — the removal of the
 * top scorer must LOWER the deadline, or a surviving device's silent
 * lapse would be reaped up to one TTL late, outside the SC-003 budget).
 * The offq-drain poller landed with T025; the watch-drain poller — with
 * T028, and the logout leg's own watch bookkeeping — with T029.
 */
@Repository
@Suppress("TooManyFunctions") // one atomic leg per PresenceStore operation: the port mirrors §1.2 + §3
class RedisPresenceStore(
    private val redisTemplate: StringRedisTemplate,
    properties: PresenceProperties,
) : PresenceStore {
    private val ttlMs: Long = properties.ttl.toMillis()
    private val hysteresisMs: Long = properties.hysteresis.toMillis()

    /**
     * SSE №18 open: ZADD alive + the watch GT-raise + the session index —
     * the returning-device revival. A PENDING offq entry is deliberately
     * LEFT in place: the offq poller's due recount (T025) is the branch
     * that cancels it AND counts the suppression (US3 AC4 — data-model
     * §1.2 «восстановление в окне → отмена (suppressed++)»).
     */
    override fun register(
        userId: UUID,
        sessionId: UUID,
        connectionId: UUID,
    ): PresenceRegistration {
        val result =
            evalList(
                REGISTER_SCRIPT,
                listOf(aliveKey(userId), connKey(userId), WATCH_KEY, lastSeenKey(userId)),
                ttlMs.toString(),
                connectionId.toString(),
                sessionId.toString(),
                userId.toString(),
            )
        val now = number(result.getOrElse(0) { null })
        val expiresAt = number(result.getOrElse(1) { null })
        return PresenceRegistration(
            connectionId = connectionId,
            userId = userId,
            sessionId = sessionId,
            expiresAt = Instant.ofEpochMilli(expiresAt),
            createdAt = Instant.ofEpochMilli(now),
        )
    }

    /** №37: one atomic extend of alive AND watch (max score, GT) plus the pub/rev TTL renew and the lastSeen stamp. */
    override fun renewRegistration(
        userId: UUID,
        connectionId: UUID,
    ): Boolean =
        evalLong(
            RENEW_SCRIPT,
            listOf(aliveKey(userId), WATCH_KEY, pubKey(userId), revKey(userId), connKey(userId), lastSeenKey(userId)),
            ttlMs.toString(),
            connectionId.toString(),
            userId.toString(),
        ) == 1L

    /**
     * SSE close: remove the member, recompute the watch score; the loss
     * of the LAST live registration only schedules the offq.
     */
    override fun unregister(
        userId: UUID,
        connectionId: UUID,
    ) {
        evalLong(
            UNREGISTER_SCRIPT,
            listOf(aliveKey(userId), connKey(userId), OFFQ_KEY, pubKey(userId), WATCH_KEY, lastSeenKey(userId)),
            hysteresisMs.toString(),
            connectionId.toString(),
            userId.toString(),
        )
    }

    /** Per-session logout: clear the session's registrations; the last one flips offline IMMEDIATELY (no offq). */
    override fun clearSessionRegistrations(
        userId: UUID,
        sessionId: UUID,
    ): PresenceTransition =
        transitionOf(
            userId,
            evalList(
                CLEAR_SESSION_SCRIPT,
                listOf(
                    aliveKey(userId),
                    connKey(userId),
                    pubKey(userId),
                    revKey(userId),
                    OFFQ_KEY,
                    ONLINE_COUNT_KEY,
                    WATCH_KEY,
                    lastSeenKey(userId),
                ),
                sessionId.toString(),
                userId.toString(),
            ),
        )

    override fun hasAliveRegistration(userId: UUID): Boolean {
        val alive = evalLong(HAS_ALIVE_SCRIPT, listOf(aliveKey(userId)))
        return alive == 1L
    }

    /** The first registration / revival: CAS nil|offline→online — rev++ and the counter bump ONLY on the flip. */
    override fun transitionOnlineIfDue(userId: UUID): PresenceTransition =
        transitionOf(
            userId,
            evalList(
                TRANSITION_ONLINE_SCRIPT,
                listOf(pubKey(userId), revKey(userId), OFFQ_KEY, ONLINE_COUNT_KEY),
                userId.toString(),
            ),
        )

    /** «Невидимка» ON: the rev advances unconditionally, the status flips offline, the offq is cancelled. */
    override fun freezePublishedOffline(userId: UUID): PresenceTransition =
        transitionOf(
            userId,
            evalList(
                FREEZE_SCRIPT,
                listOf(pubKey(userId), revKey(userId), OFFQ_KEY, ONLINE_COUNT_KEY),
                userId.toString(),
            ),
        )

    /** «Невидимка» OFF: the rev advances unconditionally, the status moves to the ACTUAL one (alive ⟹ online). */
    override fun revealPublishedStatus(userId: UUID): PresenceTransition =
        transitionOf(
            userId,
            evalList(
                REVEAL_SCRIPT,
                listOf(aliveKey(userId), pubKey(userId), revKey(userId), OFFQ_KEY, ONLINE_COUNT_KEY),
                userId.toString(),
            ),
        )

    override fun scheduleOfflinePublish(userId: UUID) {
        evalLong(SCHEDULE_OFFLINE_SCRIPT, listOf(OFFQ_KEY), hysteresisMs.toString(), userId.toString())
    }

    override fun cancelOfflinePublish(userId: UUID) {
        redisTemplate.opsForZSet().remove(OFFQ_KEY, userId.toString())
    }

    override fun dueOfflinePublishBatch(limit: Int): List<UUID> = dueBatch(OFFQ_KEY, limit)

    /** One due offq entry: a revival suppresses, the CAS publishes, an already-offline CAS is a no-op. */
    override fun processDueOfflinePublish(userId: UUID): OfflineDueOutcome {
        val result =
            evalList(
                PROCESS_DUE_SCRIPT,
                listOf(aliveKey(userId), pubKey(userId), revKey(userId), OFFQ_KEY, ONLINE_COUNT_KEY),
                userId.toString(),
            )
        return when (flag(result.getOrElse(0) { null })) {
            SUPPRESSED -> OfflineDueOutcome.Suppressed
            WENT_OFFLINE -> OfflineDueOutcome.WentOffline(rev = number(result.getOrElse(2) { null }))
            else -> OfflineDueOutcome.AlreadyOffline
        }
    }

    override fun dueWatchBatch(limit: Int): List<UUID> = dueBatch(WATCH_KEY, limit)

    override fun reapExpiredRegistrations(userId: UUID): Boolean =
        evalLong(
            REAP_SCRIPT,
            listOf(aliveKey(userId), OFFQ_KEY, WATCH_KEY, pubKey(userId)),
            hysteresisMs.toString(),
            userId.toString(),
        ) == 1L

    /**
     * №36: the published status+rev AND the raw `presence:lastseen` stamp
     * of every requested user in ONE round trip (nil pub → offline, nil
     * rev → 0, nil stamp → `lastSeenAt = null` — T043; the disclosure
     * policy is the service's business, research 008a §B2).
     */
    override fun readPublishedBatch(userIds: Collection<UUID>): Map<UUID, PublishedPresence> {
        val distinct = userIds.distinct()
        if (distinct.isEmpty()) return emptyMap()
        val keys = distinct.flatMap { listOf(pubKey(it), revKey(it), lastSeenKey(it)) }
        val flat = evalList(SNAPSHOT_SCRIPT, keys)
        return distinct
            .mapIndexed { index, userId ->
                val base = SNAPSHOT_STRIDE * index
                userId to
                    PublishedPresence(
                        userId = userId,
                        status =
                            if (flag(flat.getOrElse(base) { null }) == 1) {
                                PresencePublishedStatus.ONLINE
                            } else {
                                PresencePublishedStatus.OFFLINE
                            },
                        rev = number(flat.getOrElse(base + 1) { null }),
                        lastSeenAt =
                            flat.getOrElse(base + 2) { null }?.let { stamp ->
                                Instant.ofEpochMilli(number(stamp))
                            },
                    )
            }.toMap()
    }

    override fun readOnlineUsersCount(): Long = redisTemplate.opsForValue().get(ONLINE_COUNT_KEY)?.toLong() ?: 0L

    private fun dueBatch(
        key: String,
        limit: Int,
    ): List<UUID> = evalList(DUE_BATCH_SCRIPT, listOf(key), limit.toString()).map { UUID.fromString(it.toString()) }

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
        @Suppress("UNCHECKED_CAST") // the MULTI scripts always reply a flat list (erasure-safe)
        val typed = script as RedisScript<List<Any?>>
        return typed.let { redisTemplate.execute(it, keys, *args) }.orEmpty()
    }

    /** Maps a `{switched, statusOnline, rev}` Lua triple onto the sealed [PresenceTransition]. */
    private fun transitionOf(
        userId: UUID,
        result: List<*>,
    ): PresenceTransition {
        val status =
            if (flag(result.getOrElse(1) { null }) == 1) {
                PresencePublishedStatus.ONLINE
            } else {
                PresencePublishedStatus.OFFLINE
            }
        val rev = number(result.getOrElse(2) { null })
        return if (flag(result.getOrElse(0) { null }) == 1) {
            PresenceTransition.Switched(userId, status, rev)
        } else {
            PresenceTransition.Unchanged(userId, status, rev)
        }
    }

    private fun flag(value: Any?): Int = number(value).toInt()

    private fun number(value: Any?): Long =
        when (value) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull() ?: 0L
            else -> 0L
        }

    private fun aliveKey(userId: UUID): String = "$ALIVE_KEY_PREFIX$userId"

    private fun connKey(userId: UUID): String = "$CONN_KEY_PREFIX$userId"

    private fun pubKey(userId: UUID): String = "$PUB_KEY_PREFIX$userId"

    private fun revKey(userId: UUID): String = "$REV_KEY_PREFIX$userId"

    private fun lastSeenKey(userId: UUID): String = "$LASTSEEN_KEY_PREFIX$userId"

    private companion object {
        /** data-model 007 §3: the `presence:online:count` Lua outcome codes of the offq execution. */
        const val SUPPRESSED = 1
        const val WENT_OFFLINE = 2

        /** The SNAPSHOT Lua triple per user: {pub, rev, lastSeen} (readPublishedBatch). */
        const val SNAPSHOT_STRIDE = 3

        /** data-model 007 §3 key families (+ data-model 008a §2.2 — lastSeen). */
        const val ALIVE_KEY_PREFIX = "presence:alive:"
        const val CONN_KEY_PREFIX = "presence:conn:"
        const val PUB_KEY_PREFIX = "presence:pub:"
        const val REV_KEY_PREFIX = "presence:rev:"
        const val LASTSEEN_KEY_PREFIX = "presence:lastseen:"
        const val WATCH_KEY = "presence:watch"
        const val OFFQ_KEY = "presence:offq"
        const val ONLINE_COUNT_KEY = "presence:online:count"

        /** data-model 007 §3: pub TTL 25 h, rev TTL 7 h (milliseconds). */
        const val PUB_TTL_MS = 90_000_000L
        const val REV_TTL_MS = 25_200_000L

        /** data-model 008a §2.2: the lastSeen stamp horizon — 30 days (milliseconds), older → «давно». */
        const val LASTSEEN_TTL_MS = 2_592_000_000L

        /** The server-clock preamble of every script: scores stay comparable across pods. */
        private const val CLOCK_LUA =
            """
            local clock = redis.call('TIME')
            local now = tonumber(clock[1]) * 1000 + math.floor(tonumber(clock[2]) / 1000)
            """

        /** §3 rev semantics: INCR + TTL renew, a nil rev epoch-initialized with now_ms (research §C2). */
        private const val NEXT_REV_LUA =
            """
            local function next_rev(rev_key, now_ms)
              if redis.call('EXISTS', rev_key) == 1 then
                local value = redis.call('INCR', rev_key)
                redis.call('PEXPIRE', rev_key, $REV_TTL_MS)
                return value
              end
              redis.call('SET', rev_key, now_ms, 'PX', $REV_TTL_MS)
              return now_ms
            end
            """

        // KEYS: alive, conn, watch, lastseen; ARGV: ttlMs, connectionId, sessionId, userId → {now, expiry}
        // (watch GT: a fresh expiry may only push the shared deadline UP — never below another live max;
        //  a pending offq entry survives for the poller's due recount — the suppression must be counted;
        //  the lastSeen stamp refreshes on EVERY open — an SSE open IS presence activity, 008a §2.2)
        private val REGISTER_SCRIPT: DefaultRedisScript<List<*>> =
            DefaultRedisScript(
                """
                $CLOCK_LUA
                redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now)
                local expiry = now + tonumber(ARGV[1])
                redis.call('ZADD', KEYS[1], expiry, ARGV[2])
                redis.call('ZADD', KEYS[3], 'GT', expiry, ARGV[4])
                redis.call('HSET', KEYS[2], ARGV[2], ARGV[3])
                redis.call('PEXPIRE', KEYS[2], $PUB_TTL_MS)
                redis.call('SET', KEYS[4], now, 'PX', $LASTSEEN_TTL_MS)
                return {now, expiry}
                """.trimIndent(),
                List::class.java,
            )

        // KEYS: alive, watch, pub, rev, conn, lastseen; ARGV: ttlMs, connectionId, userId → 1|0
        // (the stamp rides ONLY the successful beat: a dead connectionId returns 0 BEFORE the SET —
        //  a rejected №37 must not move the last-activity time, 008a §2.2)
        private val RENEW_SCRIPT: DefaultRedisScript<Long> =
            DefaultRedisScript(
                """
                $CLOCK_LUA
                redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now)
                if redis.call('ZSCORE', KEYS[1], ARGV[2]) == false then
                  return 0
                end
                local expiry = now + tonumber(ARGV[1])
                redis.call('ZADD', KEYS[1], expiry, ARGV[2])
                redis.call('ZADD', KEYS[2], 'GT', expiry, ARGV[3])
                redis.call('PEXPIRE', KEYS[5], $PUB_TTL_MS)
                if redis.call('EXISTS', KEYS[3]) == 1 then
                  redis.call('PEXPIRE', KEYS[3], $PUB_TTL_MS)
                end
                if redis.call('EXISTS', KEYS[4]) == 1 then
                  redis.call('PEXPIRE', KEYS[4], $REV_TTL_MS)
                end
                redis.call('SET', KEYS[6], now, 'PX', $LASTSEEN_TTL_MS)
                return 1
                """.trimIndent(),
                Long::class.java,
            )

        // KEYS: alive, conn, offq, pub, watch, lastseen; ARGV: hysteresisMs, connectionId, userId → 1
        // (watch = MAX score of the REMAINING members — plain ZADD, the deadline may LOWER;
        //  none left → ZREM: the deferred publish is already armed in the offq;
        //  the stamp rides ONLY a close that actually REMOVED a live member — «штатное закрытие
        //  — точное», a repeat close of an unknown connectionId re-stamps nothing, 008a §2.2)
        private val UNREGISTER_SCRIPT: DefaultRedisScript<Long> =
            DefaultRedisScript(
                """
                $CLOCK_LUA
                redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now)
                local removed = redis.call('ZREM', KEYS[1], ARGV[2])
                if removed > 0 then
                  redis.call('SET', KEYS[6], now, 'PX', $LASTSEEN_TTL_MS)
                end
                redis.call('HDEL', KEYS[2], ARGV[2])
                local last = redis.call('ZRANGE', KEYS[1], -1, -1, 'WITHSCORES')
                if #last == 0 then
                  redis.call('ZREM', KEYS[5], ARGV[3])
                else
                  redis.call('ZADD', KEYS[5], tonumber(last[2]), ARGV[3])
                end
                if redis.call('EXISTS', KEYS[1]) == 0 and redis.call('GET', KEYS[4]) == 'online' then
                  redis.call('ZADD', KEYS[3], now + tonumber(ARGV[1]), ARGV[3])
                end
                return 1
                """.trimIndent(),
                Long::class.java,
            )

        // KEYS: alive, conn, pub, rev, offq, count, watch, lastseen;
        // ARGV: sessionId, userId → {switched, statusOnline, rev}
        // (watch = the unregister-leg discipline of T027: the removed top scorer must LOWER the deadline to the
        //  MAX of the REMAINING registrations — or a surviving device's silent lapse would be reaped up to one
        //  TTL late, outside the SC-003 budget; none left → ZREM, the immediate publish below replaces the offq;
        //  the stamp is UNCONDITIONAL — the logout request itself is presence activity, live registrations or
        //  not, 008a §2.2)
        private val CLEAR_SESSION_SCRIPT: DefaultRedisScript<List<*>> =
            DefaultRedisScript(
                """
                $CLOCK_LUA
                $NEXT_REV_LUA
                redis.call('SET', KEYS[8], now, 'PX', $LASTSEEN_TTL_MS)
                redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now)
                local mapping = redis.call('HGETALL', KEYS[2])
                for i = 1, #mapping, 2 do
                  if mapping[i + 1] == ARGV[1] then
                    redis.call('ZREM', KEYS[1], mapping[i])
                    redis.call('HDEL', KEYS[2], mapping[i])
                  end
                end
                local last = redis.call('ZRANGE', KEYS[1], -1, -1, 'WITHSCORES')
                if #last == 0 then
                  redis.call('ZREM', KEYS[7], ARGV[2])
                else
                  redis.call('ZADD', KEYS[7], tonumber(last[2]), ARGV[2])
                end
                local current = redis.call('GET', KEYS[3]) or 'offline'
                local rev = tonumber(redis.call('GET', KEYS[4]) or '0')
                if redis.call('EXISTS', KEYS[1]) == 0 and current == 'online' then
                  rev = next_rev(KEYS[4], now)
                  redis.call('SET', KEYS[3], 'offline', 'PX', $PUB_TTL_MS)
                  redis.call('DECR', KEYS[6])
                  redis.call('ZREM', KEYS[5], ARGV[2])
                  return {1, 0, rev}
                end
                return {0, current == 'online' and 1 or 0, rev}
                """.trimIndent(),
                List::class.java,
            )

        // KEYS: alive → 1|0 (the lazy eviction makes an expired registration never alive, FR-002)
        private val HAS_ALIVE_SCRIPT: DefaultRedisScript<Long> =
            DefaultRedisScript(
                """
                $CLOCK_LUA
                redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now)
                if redis.call('EXISTS', KEYS[1]) == 1 then
                  return 1
                end
                return 0
                """.trimIndent(),
                Long::class.java,
            )

        // KEYS: pub, rev, offq, count; ARGV: userId → {switched, statusOnline, rev}
        private val TRANSITION_ONLINE_SCRIPT: DefaultRedisScript<List<*>> =
            DefaultRedisScript(
                """
                $CLOCK_LUA
                $NEXT_REV_LUA
                if redis.call('GET', KEYS[1]) == 'online' then
                  return {0, 1, tonumber(redis.call('GET', KEYS[2]) or '0')}
                end
                local rev = next_rev(KEYS[2], now)
                redis.call('SET', KEYS[1], 'online', 'PX', $PUB_TTL_MS)
                redis.call('INCR', KEYS[4])
                redis.call('ZREM', KEYS[3], ARGV[1])
                return {1, 1, rev}
                """.trimIndent(),
                List::class.java,
            )

        // KEYS: pub, rev, offq, count; ARGV: userId → {switched, statusOnline, rev}
        private val FREEZE_SCRIPT: DefaultRedisScript<List<*>> =
            DefaultRedisScript(
                """
                $CLOCK_LUA
                $NEXT_REV_LUA
                local current = redis.call('GET', KEYS[1])
                local rev = next_rev(KEYS[2], now)
                redis.call('SET', KEYS[1], 'offline', 'PX', $PUB_TTL_MS)
                redis.call('ZREM', KEYS[3], ARGV[1])
                if current == 'online' then
                  redis.call('DECR', KEYS[4])
                  return {1, 0, rev}
                end
                return {0, 0, rev}
                """.trimIndent(),
                List::class.java,
            )

        // KEYS: alive, pub, rev, offq, count; ARGV: userId → {switched, statusOnline, rev}
        private val REVEAL_SCRIPT: DefaultRedisScript<List<*>> =
            DefaultRedisScript(
                """
                $CLOCK_LUA
                $NEXT_REV_LUA
                redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now)
                local alive = redis.call('EXISTS', KEYS[1]) == 1
                local current = redis.call('GET', KEYS[2]) or 'offline'
                local target = 'offline'
                if alive then
                  target = 'online'
                end
                local rev = next_rev(KEYS[3], now)
                if current ~= target then
                  redis.call('SET', KEYS[2], target, 'PX', $PUB_TTL_MS)
                  if target == 'online' then
                    redis.call('INCR', KEYS[5])
                  else
                    redis.call('DECR', KEYS[5])
                  end
                  redis.call('ZREM', KEYS[4], ARGV[1])
                  return {1, alive and 1 or 0, rev}
                end
                return {0, alive and 1 or 0, rev}
                """.trimIndent(),
                List::class.java,
            )

        // KEYS: alive, pub, rev, offq, count; ARGV: userId → {code, 0, rev}: 1 suppressed, 2 went offline, 3 already
        private val PROCESS_DUE_SCRIPT: DefaultRedisScript<List<*>> =
            DefaultRedisScript(
                """
                $CLOCK_LUA
                $NEXT_REV_LUA
                redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now)
                redis.call('ZREM', KEYS[4], ARGV[1])
                if redis.call('EXISTS', KEYS[1]) == 1 then
                  return {1, 0, 0}
                end
                if redis.call('GET', KEYS[2]) ~= 'online' then
                  return {3, 0, 0}
                end
                local rev = next_rev(KEYS[3], now)
                redis.call('SET', KEYS[2], 'offline', 'PX', $PUB_TTL_MS)
                redis.call('DECR', KEYS[5])
                return {2, 0, rev}
                """.trimIndent(),
                List::class.java,
            )

        // KEYS: offq; ARGV: hysteresisMs, userId → 1 (a repeat ZADD overwrites — the LATEST loss wins)
        private val SCHEDULE_OFFLINE_SCRIPT: DefaultRedisScript<Long> =
            DefaultRedisScript(
                """
                $CLOCK_LUA
                redis.call('ZADD', KEYS[1], now + tonumber(ARGV[1]), ARGV[2])
                return 1
                """.trimIndent(),
                Long::class.java,
            )

        // KEYS: a trigger ZSET (offq|watch); ARGV: limit → the due members, oldest first (fetch only)
        private val DUE_BATCH_SCRIPT: DefaultRedisScript<List<*>> =
            DefaultRedisScript(
                """
                $CLOCK_LUA
                return redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', now, 'LIMIT', 0, tonumber(ARGV[1]))
                """.trimIndent(),
                List::class.java,
            )

        // KEYS: alive, offq, watch, pub; ARGV: hysteresisMs, userId → 1 ⟺ emptied into the offq (T028)
        private val REAP_SCRIPT: DefaultRedisScript<Long> =
            DefaultRedisScript(
                """
                $CLOCK_LUA
                redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now)
                if redis.call('EXISTS', KEYS[1]) == 1 then
                  return 0
                end
                redis.call('ZREM', KEYS[3], ARGV[2])
                if redis.call('GET', KEYS[4]) == 'online' then
                  redis.call('ZADD', KEYS[2], now + tonumber(ARGV[1]), ARGV[2])
                  return 1
                end
                return 0
                """.trimIndent(),
                Long::class.java,
            )

        // KEYS: pub1, rev1, lastseen1, pub2, rev2, lastseen2, … → {statusFlag1, rev1, lastSeen1|nil, …}
        // (nil pub → offline, nil rev → 0; the raw stamp GET: a missing key replies false → a nil list element —
        //  a nil in the table would punch a hole and truncate the reply, `false` travels safely, 008a §2.2)
        private val SNAPSHOT_SCRIPT: DefaultRedisScript<List<*>> =
            DefaultRedisScript(
                """
                local result = {}
                for i = 1, #KEYS, 3 do
                  local status = 0
                  if redis.call('GET', KEYS[i]) == 'online' then
                    status = 1
                  end
                  result[#result + 1] = status
                  result[#result + 1] = tonumber(redis.call('GET', KEYS[i + 1]) or '0')
                  result[#result + 1] = redis.call('GET', KEYS[i + 2])
                end
                return result
                """.trimIndent(),
                List::class.java,
            )
    }
}
