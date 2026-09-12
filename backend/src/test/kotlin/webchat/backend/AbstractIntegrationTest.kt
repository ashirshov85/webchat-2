package webchat.backend

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

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
            registry.add("auth.jwt.keys") { "it-test=it-test-p256-pem" }
            registry.add("auth.jwt.active-kid") { "it-test" }
        }
    }
}
