package webchat.backend.auth.ratelimit

import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import java.io.BufferedReader
import java.nio.charset.StandardCharsets

/**
 * Replays a request body that [RateLimitFilter] has already consumed while
 * extracting the rate-limit identifier — the downstream MVC message
 * converters re-read the very same bytes. An implementation detail of the
 * filter, hence package-private.
 */
internal class CachedBodyRequestWrapper(
    request: HttpServletRequest,
    private val body: ByteArray,
) : HttpServletRequestWrapper(request) {
    override fun getInputStream(): ServletInputStream = CachedBodyServletInputStream(body)

    override fun getReader(): BufferedReader = getInputStream().reader(StandardCharsets.UTF_8).buffered()

    private class CachedBodyServletInputStream(
        private val body: ByteArray,
    ) : ServletInputStream() {
        private var index = 0

        override fun isFinished(): Boolean = index >= body.size

        override fun isReady(): Boolean = true

        override fun setReadListener(readListener: ReadListener?) = Unit

        override fun read(): Int = if (index >= body.size) END_OF_STREAM else body[index++].toInt() and BYTE_MASK
    }

    private companion object {
        const val END_OF_STREAM = -1
        const val BYTE_MASK = 0xff
    }
}
