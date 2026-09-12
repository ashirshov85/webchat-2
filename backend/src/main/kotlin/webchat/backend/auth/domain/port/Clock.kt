package webchat.backend.auth.domain.port

import java.time.Instant

fun interface Clock {
    fun now(): Instant
}
