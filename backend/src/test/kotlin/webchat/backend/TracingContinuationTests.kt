package webchat.backend

import io.opentelemetry.sdk.trace.SpanProcessor
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Bean
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import java.time.Duration

@AutoConfigureObservability
class TracingContinuationTests(
    @Autowired private val restTemplate: TestRestTemplate,
    @Autowired private val exporter: CollectingSpanExporter,
) : AbstractIntegrationTest() {
    @Test
    fun httpServerSpanContinuesIncomingTraceparent() {
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
            assertThat(exporter.spans.map { span -> span.spanContext.traceId }).contains(traceId)
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class CollectorConfig {
        @Bean
        fun collectingSpanExporter(): CollectingSpanExporter = CollectingSpanExporter()

        @Bean
        fun spanProcessor(exporter: CollectingSpanExporter): SpanProcessor = SimpleSpanProcessor.create(exporter)
    }
}
