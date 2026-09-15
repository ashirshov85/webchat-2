package webchat.backend.auth.ratelimit

import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Component
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Request-source identification for the US5 limits and the security journal
 * (research.md §7): the client IP is the LEFTMOST `X-Forwarded-For` hop —
 * valid because exactly one trusted ingress appends the header — with a
 * `remoteAddr` fallback for direct dev access. IPv6 sources are normalized
 * to their /64 prefix so address rotation within one allocation cannot
 * spread a client across buckets.
 *
 * Compatible with `server.forward-headers-strategy=framework`: when the
 * ForwardedHeaderFilter rewrites `remoteAddr` from XFF and strips the
 * header, the fallback returns the same leftmost hop. Only the SHA-256
 * peppered hash of the resolved value is ever persisted (FR-013).
 */
@Component
class ClientIpResolver {
    /** XFF leftmost hop with a remoteAddr fallback, IPv6 normalized to /64. */
    fun resolve(request: HttpServletRequest): String {
        val leftmostHop = request.getHeader(X_FORWARDED_FOR_HEADER)?.substringBefore(COMMA)?.trim()
        if (!leftmostHop.isNullOrEmpty()) return normalize(leftmostHop)
        return normalize(request.remoteAddr)
    }

    /**
     * Normalizes a raw IP literal: strips the bracketed/port and zone-id
     * forms, collapses IPv6 onto its /64 network. Values that are not IP
     * literals are returned verbatim — a distinct key per distinct garbage
     * beats funnelling spoofed traffic onto one shared bucket.
     */
    fun normalize(rawIp: String): String {
        val candidate = extractBareIp(rawIp)
        if (!candidate.contains(IPV6_DELIMITER)) return candidate
        return runCatching { toIpv6Slash64(candidate) }.getOrDefault(candidate)
    }

    private fun extractBareIp(ip: String): String {
        val trimmed = ip.trim()
        if (!trimmed.startsWith(BRACKET_START)) return trimmed.substringBefore(ZONE_DELIMITER)
        return trimmed
            .substringAfter(BRACKET_START)
            .substringBefore(BRACKET_END)
            .substringBefore(ZONE_DELIMITER)
    }

    private fun toIpv6Slash64(candidate: String): String {
        // Literal-only parse: a hostname cannot contain ':', so no DNS
        // resolution can be triggered by attacker-supplied XFF values.
        val address = InetAddress.getByName(candidate)
        if (address !is Inet6Address) return address.hostAddress
        val networkBytes = address.address.copyOf(IPV6_BYTE_LENGTH)
        networkBytes.fill(HOST_BYTE_MASK, HOST_BYTE_OFFSET, IPV6_BYTE_LENGTH)
        return formatIpv6(networkBytes)
    }

    /** RFC 5952 rendering with `::` for the longest zero-group run (ties: first). */
    private fun formatIpv6(bytes: ByteArray): String {
        val groups = IntArray(IPV6_GROUP_COUNT) { index -> bytes.toGroup(index) }
        val compression = longestZeroRunOfAtLeastTwo(groups)
        if (compression == null) {
            return groups.joinToString(GROUP_DELIMITER, transform = ::hexGroup)
        }
        val groupsAsList = groups.asList()
        val head = groupsAsList.subList(0, compression.first).joinToString(GROUP_DELIMITER, transform = ::hexGroup)
        val tail =
            groupsAsList
                .subList(compression.second + 1, IPV6_GROUP_COUNT)
                .joinToString(GROUP_DELIMITER, transform = ::hexGroup)
        return head + IPV6_COMPRESSION + tail
    }

    private fun hexGroup(group: Int): String = Integer.toHexString(group)

    private fun ByteArray.toGroup(index: Int): Int =
        ((this[index * BYTES_PER_GROUP].toInt() and BYTE_MASK) shl BYTE_SHIFT) or
            (this[index * BYTES_PER_GROUP + 1].toInt() and BYTE_MASK)

    private fun longestZeroRunOfAtLeastTwo(groups: IntArray): Pair<Int, Int>? {
        var bestStart = NO_RUN
        var bestLength = 0
        var currentStart = NO_RUN
        groups.forEachIndexed { index, group ->
            if (group == 0) {
                if (currentStart == NO_RUN) currentStart = index
                val length = index - currentStart + 1
                if (length > bestLength) {
                    bestLength = length
                    bestStart = currentStart
                }
            } else {
                currentStart = NO_RUN
            }
        }
        if (bestLength < MIN_COMPRESSIBLE_RUN) return null
        return bestStart to bestStart + bestLength - 1
    }

    private companion object {
        const val X_FORWARDED_FOR_HEADER = "X-Forwarded-For"
        const val COMMA = ","
        const val IPV6_DELIMITER = ":"
        const val GROUP_DELIMITER = ":"
        const val IPV6_COMPRESSION = "::"
        const val BRACKET_START = "["
        const val BRACKET_END = "]"
        const val ZONE_DELIMITER = "%"
        const val IPV6_BYTE_LENGTH = 16
        const val IPV6_GROUP_COUNT = 8
        const val BYTES_PER_GROUP = 2
        const val BYTE_MASK = 0xff
        const val BYTE_SHIFT = 8
        const val HOST_BYTE_OFFSET = 8
        const val HOST_BYTE_MASK: Byte = 0
        const val MIN_COMPRESSIBLE_RUN = 2
        const val NO_RUN = -1
    }
}
