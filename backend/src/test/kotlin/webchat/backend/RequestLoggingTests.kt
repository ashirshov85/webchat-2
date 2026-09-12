package webchat.backend

import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import java.time.Duration

@ExtendWith(OutputCaptureExtension::class)
@AutoConfigureObservability
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
class RequestLoggingTests(
    @Autowired private val restTemplate: TestRestTemplate,
) {
    @Test
    fun requestLogRecordCarriesIncomingTraceId(capturedOutput: CapturedOutput) {
        val traceId = "0af7651916cd43dd8448eb211c80319c"
        val parentSpanId = "b7ad6b7169203331"
        val headers = HttpHeaders()
        headers.add("traceparent", "00-$traceId-$parentSpanId-01")

        restTemplate.exchange(
            "/actuator/health/liveness",
            HttpMethod.GET,
            HttpEntity<Void>(headers),
            String::class.java,
        )

        await().atMost(Duration.ofSeconds(15)).untilAsserted {
            val record =
                capturedOutput.all
                    .lineSequence()
                    .firstOrNull { line -> line.contains(traceId) }

            assertThat(record).isNotNull
            // Machine-readable single-line JSON (FR-011, SC-005)
            assertThat(record).contains("\"@timestamp\":")
            assertThat(record).contains("\"level\":")
            assertThat(record).contains("\"message\":")
            assertThat(record).contains("\"service\":\"backend\"")
            // Request-scoped fields from MDC (US5-1)
            assertThat(record).contains("\"traceId\":\"$traceId\"")
            assertThat(record).contains("\"spanId\":")
            // The record describes the handled request
            assertThat(record).contains("GET")
            assertThat(record).contains("/actuator/health/liveness")
            assertThat(record).contains("200")
        }
    }
}
