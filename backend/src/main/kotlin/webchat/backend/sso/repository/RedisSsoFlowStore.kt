package webchat.backend.sso.repository

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Repository
import webchat.backend.config.SsoProperties
import webchat.backend.sso.domain.port.SsoFlowContext
import webchat.backend.sso.domain.port.SsoFlowStore
import webchat.backend.sso.domain.port.SsoHandshake
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration

/**
 * Redis adapter for [SsoFlowStore] (T013, data-model.md §5, research.md §2–§3):
 * keeps the ephemeral single-use SSO state outside the process (constitution II)
 * as JSON strings under `sso:flow:<state>` (TTL `sso.flow-ttl`) and
 * `sso:handshake:<sha256(code)>` (TTL `sso.handshake-ttl`).
 *
 * Single-use is enforced by atomic `GETDEL` take-outs: a replayed, forged or
 * foreign-tab request finds nothing and the caller rejects it BEFORE any side
 * effect (SC-006, FR-012). Only the SHA-256 of a handshake code ever becomes
 * a key — the raw code is never persisted (research.md §2).
 *
 * A consumed-but-unreadable payload is reported as absent (warn, no payload
 * values in the log — nonce/codeVerifier are flow secrets, SC-004/FR-011): the
 * caller follows the same rejection path as for an unknown state. Redis
 * connectivity errors propagate to the caller; losing Redis never breaks PG
 * durability — live flows simply expire and the user retries (data-model.md §5).
 */
@Repository
class RedisSsoFlowStore(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    properties: SsoProperties,
) : SsoFlowStore {
    private val flowTtl: Duration = properties.flowTtl
    private val handshakeTtl: Duration = properties.handshakeTtl

    override fun saveFlow(
        state: String,
        context: SsoFlowContext,
    ) {
        redisTemplate
            .opsForValue()
            .set(flowKey(state), objectMapper.writeValueAsString(context), flowTtl)
    }

    override fun consumeFlow(state: String): SsoFlowContext? = read(flowKey(state), SsoFlowContext::class.java)

    override fun saveHandshake(
        code: String,
        handshake: SsoHandshake,
    ) {
        redisTemplate
            .opsForValue()
            .set(handshakeKey(code), objectMapper.writeValueAsString(handshake), handshakeTtl)
    }

    override fun consumeHandshake(code: String): SsoHandshake? = read(handshakeKey(code), SsoHandshake::class.java)

    private fun <T : Any> read(
        key: String,
        type: Class<T>,
    ): T? {
        val json =
            redisTemplate.opsForValue().getAndDelete(key)
                ?: return null
        return runCatching { objectMapper.readValue(json, type) }
            .onFailure {
                log.warn(
                    "SSO {} payload is unreadable, treating as absent",
                    key.substringBefore(KEY_SEPARATOR),
                )
            }.getOrNull()
    }

    private fun flowKey(state: String): String = "$FLOW_KEY_PREFIX$state"

    private fun handshakeKey(code: String): String = "$HANDSHAKE_KEY_PREFIX${sha256Hex(code)}"

    private fun sha256Hex(value: String): String =
        MessageDigest
            .getInstance(SHA_256)
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = EMPTY_STRING) { BYTE_TO_HEX_FORMAT.format(it) }

    private companion object {
        private val log = LoggerFactory.getLogger(RedisSsoFlowStore::class.java)

        /** data-model.md §5 key families. */
        const val FLOW_KEY_PREFIX = "sso:flow:"
        const val HANDSHAKE_KEY_PREFIX = "sso:handshake:"
        const val KEY_SEPARATOR = ":"

        const val SHA_256 = "SHA-256"
        const val BYTE_TO_HEX_FORMAT = "%02x"
        const val EMPTY_STRING = ""
    }
}
