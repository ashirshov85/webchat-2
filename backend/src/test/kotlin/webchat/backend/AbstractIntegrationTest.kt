package webchat.backend

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Suppress("UtilityClassWithPublicConstructor")
abstract class AbstractIntegrationTest {
    companion object {
        @ServiceConnection
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(DockerImageName.parse("postgres:17"))
                .withDatabaseName("webchat")
                .withUsername("webchat")
                .withPassword("webchat")

        @ServiceConnection
        val redis: GenericContainer<*> =
            GenericContainer(DockerImageName.parse("redis:7"))
                .withExposedPorts(6379)

        @DynamicPropertySource
        @JvmStatic
        fun authTestProperties(registry: DynamicPropertyRegistry) {
            registry.add("auth.ip-hash-pepper") { "it-test-pepper" }
            registry.add("auth.jwt.keys") { TEST_JWT_KEYS }
            registry.add("auth.jwt.active-kid") { TEST_JWT_KID }
        }

        // Since T028 JwtService parses auth.jwt.keys at startup and fails fast
        // on placeholders, tests sign with an ephemeral P-256 key (PKCS#8 PEM,
        // Base64-wrapped for the single-line kid=PEM form of the env mapping).
        // Internal since T037: AuthenticationBoundaryIT re-uses the SAME key to
        // mint specially-crafted tokens (expired, wrong `typ`) that must pass
        // the signature gate and hit the deeper verification branches.
        internal const val TEST_JWT_KID = "it-test"

        internal val TEST_JWT_KEYS: String by lazy {
            val keyPair =
                KeyPairGenerator
                    .getInstance("EC")
                    .apply { initialize(ECGenParameterSpec("secp256r1")) }
                    .generateKeyPair()
            "$TEST_JWT_KID=" + Base64.getEncoder().encodeToString(pkcs8Pem(keyPair.private).toByteArray())
        }

        private fun pkcs8Pem(privateKey: PrivateKey): String {
            val body =
                Base64
                    .getMimeEncoder(PEM_LINE_LENGTH, "\n".toByteArray())
                    .encodeToString(privateKey.encoded)
            return "-----BEGIN PRIVATE KEY-----\n$body\n-----END PRIVATE KEY-----"
        }

        private const val PEM_LINE_LENGTH = 64
    }
}
