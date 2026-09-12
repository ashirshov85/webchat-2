package webchat.backend

import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
import org.springframework.context.ApplicationContext

@AutoConfigureObservability
class BackendApplicationTests(
    @Autowired private val context: ApplicationContext,
) : AbstractIntegrationTest() {
    @Test
    fun contextLoads() {
        assertThat(context.getBean(BackendApplication::class.java)).isNotNull
    }

    @Test
    fun otlpSpanExporterIsConfigured() {
        assertThat(context.getBean(OtlpHttpSpanExporter::class.java)).isNotNull()
    }
}
