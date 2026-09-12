package webchat.backend

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpStatus

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
            "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration," +
            "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration," +
            "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration," +
            "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration," +
            "org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration," +
            "org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration",
    ],
)
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
    fun prometheusExposesHttpServerRequestsHistogram() {
        restTemplate.getForEntity("/actuator/health", String::class.java)

        val response = restTemplate.getForEntity("/actuator/prometheus", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("http_server_requests_seconds_bucket")
        assertThat(response.body).contains("http_server_requests_seconds_count")
        assertThat(response.body).contains("http_server_requests_seconds_sum")
    }

    @Test
    fun httpServerRequestsMetricCarriesRequiredTags() {
        restTemplate.getForEntity("/actuator/health", String::class.java)

        val response = restTemplate.getForEntity("/actuator/prometheus", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("method=\"GET\"")
        assertThat(response.body).contains("uri=")
        assertThat(response.body).contains("status=\"200\"")
        assertThat(response.body).contains("outcome=\"SUCCESS\"")
        assertThat(response.body).contains("exception=\"none\"")
        assertThat(response.body).contains("application=\"backend\"")
    }
}
