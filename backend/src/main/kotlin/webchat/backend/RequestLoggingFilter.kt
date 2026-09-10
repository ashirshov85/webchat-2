package webchat.backend

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Emits one structured log record per handled HTTP request so that every
 * processed request leaves a log entry carrying the active traceId/spanId
 * from MDC (FR-011, US5-1, SC-005; contracts/technical-endpoints.md §1).
 *
 * Ordered last to run inside the observation/tracing scope: the record is
 * written after the chain completes, while the server span is still active.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
class RequestLoggingFilter : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(RequestLoggingFilter::class.java)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val startNanos = System.nanoTime()
        try {
            filterChain.doFilter(request, response)
        } finally {
            val durationMs = (System.nanoTime() - startNanos) / NANOS_PER_MILLISECOND
            log.info(
                "HTTP {} {} -> {} ({} ms)",
                request.method,
                request.requestURI,
                response.status,
                durationMs,
            )
        }
    }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
