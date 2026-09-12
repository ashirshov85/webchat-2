package webchat.backend.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SecurityConfigPasswordEncoderTest {
    private val encoder = SecurityConfig().passwordEncoder()

    @Test
    fun encodesWithArgon2PrefixOwaspParamsAndUniqueSalt() {
        val hash = encoder.encode("correct horse battery staple")

        assertThat(hash).startsWith("{argon2}")
        assertThat(hash).contains("m=19456,t=2,p=1")
        assertThat(encoder.encode("correct horse battery staple")).isNotEqualTo(hash)
    }

    @Test
    fun matchesVerifiesPassword() {
        val hash = encoder.encode("Str0ng-Passphrase!")

        assertThat(encoder.matches("Str0ng-Passphrase!", hash)).isTrue()
        assertThat(encoder.matches("wrong-passphrase", hash)).isFalse()
    }

    @Test
    fun freshHashDoesNotRequireUpgrade() {
        val hash = encoder.encode("Str0ng-Passphrase!")

        assertThat(encoder.upgradeEncoding(hash)).isFalse()
    }
}
