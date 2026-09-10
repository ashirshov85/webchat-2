import 'zone.js'

import { ZoneContextManager } from '@opentelemetry/context-zone'
import { W3CTraceContextPropagator } from '@opentelemetry/core'
import { OTLPTraceExporter } from '@opentelemetry/exporter-trace-otlp-http'
import { registerInstrumentations } from '@opentelemetry/instrumentation'
import { FetchInstrumentation } from '@opentelemetry/instrumentation-fetch'
import { resourceFromAttributes } from '@opentelemetry/resources'
import { BatchSpanProcessor, WebTracerProvider } from '@opentelemetry/sdk-trace-web'

export function initTracing(): void {
  const tracesEndpoint = import.meta.env.VITE_OTEL_EXPORTER_OTLP_TRACES_ENDPOINT

  const exporter = new OTLPTraceExporter(
    tracesEndpoint === undefined ? {} : { url: tracesEndpoint },
  )

  const provider = new WebTracerProvider({
    resource: resourceFromAttributes({
      'service.name': 'frontend',
    }),
    spanProcessors: [new BatchSpanProcessor(exporter)],
  })
  provider.register({
    contextManager: new ZoneContextManager(),
    propagator: new W3CTraceContextPropagator(),
  })

  registerInstrumentations({
    instrumentations: [
      new FetchInstrumentation({
        ignoreUrls: [/\/v1\/traces$/],
        propagateTraceHeaderCorsUrls: [/.*/],
      }),
    ],
  })
}
