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
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(DockerImageName.parse("postgres:17"))
                .withDatabaseName("webchat")
                .withUsername("webchat")
                .withPassword("webchat")
                .withInitScript("webchat_it_init.sql")
                // A full `gradlew check` run caches many distinct Spring
                // contexts (each IT class with unique test properties gets
                // its own), and every cached context keeps a Hikari pool
                // (10 connections by default) open against this single
                // shared container. The default max_connections=100 is
                // exhausted near the end of the run, killing context
                // startup with "remaining connection slots are reserved
                // for roles with the SUPERUSER attribute" (the app role
                // is NON-superuser, see webchat_it_init.sql). Test-only
                // infrastructure knob; production pools are unchanged.
                .withCommand("postgres", "-c", "max_connections=400")
                .apply { start() }

        @ServiceConnection
        val redis: GenericContainer<*> =
            GenericContainer(DockerImageName.parse("redis:7"))
                .withExposedPorts(6379)

        @DynamicPropertySource
        @JvmStatic
        fun integrationTestProperties(registry: DynamicPropertyRegistry) {
            // The app pool logs in as the dedicated NON-superuser role the
            // container init script creates (webchat_it_init.sql), NOT the
            // Testcontainers bootstrap superuser: superusers bypass
            // row-level security even under FORCE, and the RLS-based
            // per-chat fault injection of SyncIT (T008, all-or-refusal)
            // must reach the app sessions. PG 15+ forbids demoting the
            // bootstrap superuser, so the app role is separate from the
            // start; Flyway creates and owns every schema object through
            // this same login (as production-like as it gets). The
            // ConnectionDetails beans of @ServiceConnection would override
            // these properties, so postgres is wired explicitly — and
            // eagerly started above, since without @ServiceConnection
            // nothing else starts it before the lazy suppliers resolve.
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { APP_DB_ROLE }
            registry.add("spring.datasource.password") { APP_DB_ROLE }
            registry.add("auth.ip-hash-pepper") { "it-test-pepper" }
            registry.add("auth.jwt.keys") { TEST_JWT_KEYS }
            registry.add("auth.jwt.active-kid") { TEST_JWT_KID }
            // The IT context never runs an SMTP listener (delivery is asserted
            // through the outbox table, not a socket), so the MailHealthIndicator
            // would report DOWN and flip the aggregate /actuator/health to 503 —
            // on dev machines with a local Mailpit the same IT is UP, i.e. the
            // T037 boundary-audit health assertions were environment-coupled.
            // Mail reachability is out of IT scope: disabled for determinism,
            // db/redis stay covered by the real Testcontainers.
            registry.add("management.health.mail.enabled") { "false" }
        }

        // Since T028 JwtService parses auth.jwt.keys at startup and fails fast
        // on placeholders, tests sign with an ephemeral P-256 key (PKCS#8 PEM,
        // Base64-wrapped for the single-line kid=PEM form of the env mapping).
        // Internal since T037: AuthenticationBoundaryIT re-uses the SAME key to
        // mint specially-crafted tokens (expired, wrong `typ`) that must pass
        // the signature gate and hit the deeper verification branches.
        internal const val TEST_JWT_KID = "it-test"

        /** The non-superuser app login created by webchat_it_init.sql (T008 fault-injection prerequisite). */
        internal const val APP_DB_ROLE = "webchat_app"

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
