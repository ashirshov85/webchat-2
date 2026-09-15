package webchat.backend.auth.ratelimit

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest

/**
 * Unit-level mirror of research.md §7 (T046): XFF leftmost with the single
 * trusted ingress hop, remoteAddr fallback, IPv6 /64 normalization. The
 * end-to-end rate-limit behaviour lives in RateLimitIT (T045a).
 */
class ClientIpResolverTest {
    private val resolver = ClientIpResolver()

    @Test
    fun `resolves the leftmost xff hop`() {
        val request = request(remoteAddr = "10.0.0.9", xForwardedFor = "203.0.113.7, 10.0.0.8")

        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.7")
    }

    @Test
    fun `falls back to remoteaddr without xff or on blank hop`() {
        assertThat(resolver.resolve(request(remoteAddr = "192.0.2.10"))).isEqualTo("192.0.2.10")
        assertThat(resolver.resolve(request(remoteAddr = "192.0.2.11", xForwardedFor = " , 10.0.0.8")))
            .isEqualTo("192.0.2.11")
    }

    @Test
    fun `keeps ipv4 sources untouched`() {
        assertThat(resolver.normalize("198.18.0.1")).isEqualTo("198.18.0.1")
    }

    @Test
    fun `collapses ipv6 sources onto their slash 64 prefix`() {
        assertThat(resolver.normalize("2001:db8:1234:5678:aaaa:bbbb:cccc:dddd"))
            .isEqualTo("2001:db8:1234:5678::")
        assertThat(resolver.normalize("2001:db8:1234:5678::1"))
            .isEqualTo("2001:db8:1234:5678::")
    }

    @Test
    fun `ipv6 hosts sharing a prefix share the bucket key`() {
        val first = resolver.normalize("2001:db8:1234:5678:0:0:0:1")
        val second = resolver.normalize("2001:db8:1234:5678:ffff:ffff:ffff:ffff")
        assertThat(first).isEqualTo(second)

        val otherNetwork = resolver.normalize("2001:db8:1234:5679::1")
        assertThat(otherNetwork).isNotEqualTo(first)
    }

    @Test
    fun `strips bracketed port and zone id forms`() {
        assertThat(resolver.normalize("[2001:db8:1234:5678::1]:8443")).isEqualTo("2001:db8:1234:5678::")
        assertThat(resolver.normalize("fe80::1%25eth0")).isEqualTo("fe80::")
        assertThat(resolver.normalize("fe80::1%eth0")).isEqualTo("fe80::")
    }

    @Test
    fun `unparseable values stay distinct instead of sharing one key`() {
        assertThat(resolver.normalize("not-an-ip")).isEqualTo("not-an-ip")
        assertThat(resolver.normalize("::zzz")).isEqualTo("::zzz")
    }

    private fun request(
        remoteAddr: String,
        xForwardedFor: String? = null,
    ): MockHttpServletRequest =
        MockHttpServletRequest().apply {
            this.remoteAddr = remoteAddr
            xForwardedFor?.let { addHeader("X-Forwarded-For", it) }
        }
}
