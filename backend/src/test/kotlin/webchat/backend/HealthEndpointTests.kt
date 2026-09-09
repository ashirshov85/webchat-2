package webchat.backend

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpStatus

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HealthEndpointTests(
    @Autowired private val restTemplate: TestRestTemplate,
) {
    @Test
    fun livenessProbeIsUp() {
        val response = restTemplate.getForEntity("/actuator/health/liveness", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("\"status\":\"UP\"")
    }

    @Test
    fun readinessProbeIsUp() {
        val response = restTemplate.getForEntity("/actuator/health/readiness", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("\"status\":\"UP\"")
    }

    @Test
    fun prometheusExposesHttpServerRequestsMetric() {
        restTemplate.getForEntity("/actuator/health", String::class.java)

        val response = restTemplate.getForEntity("/actuator/prometheus", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("http_server_requests_seconds")
    }
}
