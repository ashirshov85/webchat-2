package webchat.backend

import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SpanExporter
import java.util.concurrent.ConcurrentLinkedQueue

class CollectingSpanExporter : SpanExporter {
    val spans = ConcurrentLinkedQueue<SpanData>()

    override fun export(spans: MutableCollection<SpanData>): CompletableResultCode {
        this.spans.addAll(spans)
        return CompletableResultCode.ofSuccess()
    }

    override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()

    override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
}
