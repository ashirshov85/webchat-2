package webchat.backend.presence.api

import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * RFC 9457 rendering for the presence API errors (contracts/presence-api.md
 * §4; the same conventions as the chats/contacts advices): every failure
 * leaves the controller as a typed exception and reaches the client as
 * `application/problem+json` with the contract code inside
 * `errors: map<string, string[]>` — codes and field names only, never a
 * connectionId or any payload (constitution V).
 *
 * The advice claims exactly its own carrier types, so the global advices
 * never collide; the №38 settings legs (T032) extend it last.
 */
@RestControllerAdvice
class PresenceExceptionHandler {
    /**
     * 400 (openapi №36): `userIds` is absent/empty or any segment is
     * not a UUID — `errors: {userIds: [malformed_request]}`.
     */
    @ExceptionHandler(SnapshotMalformedException::class)
    fun onSnapshotMalformed(failure: SnapshotMalformedException): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, failure.message ?: SNAPSHOT_MALFORMED_DETAIL)
            .apply { setProperty(ERRORS_PROPERTY, mapOf(USER_IDS_FIELD to listOf(MALFORMED_REQUEST_CODE))) }

    /**
     * 400 (openapi №36, presence-api.md §1): the batch exceeds the cap
     * after dedup — the >200 chunking is the CLIENT's contract business,
     * so the server refuses the oversized batch whole; the ids
     * themselves are never echoed (constitution V).
     */
    @ExceptionHandler(PresenceIdsTooManyException::class)
    fun onPresenceIdsTooMany(): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, IDS_TOO_MANY_DETAIL)
            .apply { setProperty(ERRORS_PROPERTY, mapOf(USER_IDS_FIELD to listOf(IDS_TOO_MANY_CODE))) }

    /**
     * 429 (openapi №36, presence-api.md §1): the per-user snapshot
     * bucket is exhausted — `Retry-After` carries the integral seconds
     * to the next token; the client repeats the batch after the wait
     * (the №18 stream keeps delivering and the max(rev) merge of FR-003
     * absorbs the delay). The targets themselves are never echoed.
     */
    @ExceptionHandler(PresenceSnapshotFloodException::class)
    fun onSnapshotFlood(failure: PresenceSnapshotFloodException): ResponseEntity<ProblemDetail> =
        ResponseEntity
            .status(HttpStatus.TOO_MANY_REQUESTS)
            .header(HttpHeaders.RETRY_AFTER, failure.retryAfterSeconds.toString())
            .body(
                problem(HttpStatus.TOO_MANY_REQUESTS, SNAPSHOT_FLOOD_DETAIL)
                    .apply { setProperty(ERRORS_PROPERTY, mapOf(USER_IDS_FIELD to listOf(FLOOD_LIMIT_CODE))) },
            )

    /** 400 (openapi №37): the body is absent, connectionId is not set or is not a UUID. */
    @ExceptionHandler(HeartbeatMalformedException::class)
    fun onHeartbeatMalformed(failure: HeartbeatMalformedException): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, failure.message ?: MALFORMED_DETAIL)
            .apply { setProperty(ERRORS_PROPERTY, mapOf(CONNECTION_ID_FIELD to listOf(MALFORMED_REQUEST_CODE))) }

    /** 404 (openapi №37): unknown/expired/foreign connectionId — the client reconnects №18. */
    @ExceptionHandler(HeartbeatConnectionNotFoundException::class)
    fun onHeartbeatConnectionNotFound(): ProblemDetail =
        problem(HttpStatus.NOT_FOUND, CONNECTION_NOT_FOUND_DETAIL)
            .apply { setProperty(ERRORS_PROPERTY, mapOf(CONNECTION_ID_FIELD to listOf(CONNECTION_NOT_FOUND_CODE))) }

    /**
     * 429 (openapi №37, presence-api.md §2): the per-user heartbeat
     * bucket is exhausted — `Retry-After` carries the integral seconds
     * to the next token; the client keeps the №18 stream and retries in
     * the next interval (the TTL headroom makes the wait safe). The
     * connectionId itself is never echoed (constitution V).
     */
    @ExceptionHandler(PresenceHeartbeatFloodException::class)
    fun onHeartbeatFlood(failure: PresenceHeartbeatFloodException): ResponseEntity<ProblemDetail> =
        ResponseEntity
            .status(HttpStatus.TOO_MANY_REQUESTS)
            .header(HttpHeaders.RETRY_AFTER, failure.retryAfterSeconds.toString())
            .body(
                problem(HttpStatus.TOO_MANY_REQUESTS, FLOOD_LIMIT_DETAIL)
                    .apply { setProperty(ERRORS_PROPERTY, mapOf(CONNECTION_ID_FIELD to listOf(FLOOD_LIMIT_CODE))) },
            )

    /**
     * 400 (openapi №38, presence-api.md §3): the body is absent,
     * `incognito` is not set or is not a boolean —
     * `errors: {incognito: [malformed_request]}`.
     */
    @ExceptionHandler(SettingsMalformedException::class)
    fun onSettingsMalformed(failure: SettingsMalformedException): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, failure.message ?: SETTINGS_MALFORMED_DETAIL)
            .apply { setProperty(ERRORS_PROPERTY, mapOf(INCOGNITO_FIELD to listOf(MALFORMED_REQUEST_CODE))) }

    /**
     * 429 (openapi №38, presence-api.md §3): the per-user PUT bucket is
     * exhausted — `Retry-After` carries the integral seconds to the
     * next token; the stored mode is NOT touched and the client repeats
     * the toggle after the wait. The value itself is never echoed
     * (constitution V).
     */
    @ExceptionHandler(PresenceSettingsFloodException::class)
    fun onSettingsFlood(failure: PresenceSettingsFloodException): ResponseEntity<ProblemDetail> =
        ResponseEntity
            .status(HttpStatus.TOO_MANY_REQUESTS)
            .header(HttpHeaders.RETRY_AFTER, failure.retryAfterSeconds.toString())
            .body(
                problem(HttpStatus.TOO_MANY_REQUESTS, SETTINGS_FLOOD_DETAIL)
                    .apply { setProperty(ERRORS_PROPERTY, mapOf(INCOGNITO_FIELD to listOf(FLOOD_LIMIT_CODE))) },
            )

    private fun problem(
        status: HttpStatus,
        detail: String,
    ): ProblemDetail =
        ProblemDetail.forStatus(status).apply {
            title = status.reasonPhrase
            this.detail = detail
        }

    private companion object {
        const val ERRORS_PROPERTY = "errors"
        const val CONNECTION_ID_FIELD = "connectionId"
        const val USER_IDS_FIELD = "userIds"
        const val INCOGNITO_FIELD = "incognito"
        const val MALFORMED_REQUEST_CODE = "malformed_request"
        const val CONNECTION_NOT_FOUND_CODE = "presence_connection_not_found"
        const val IDS_TOO_MANY_CODE = "presence_ids_too_many"
        const val FLOOD_LIMIT_CODE = "flood_limit"
        const val MALFORMED_DETAIL = "connectionId must be present and a UUID"
        const val SNAPSHOT_MALFORMED_DETAIL = "userIds must be present, non-empty and a comma-separated UUID list"
        const val IDS_TOO_MANY_DETAIL =
            "The userIds batch exceeds the per-request cap of 200 targets after dedup; chunk the batch client-side"
        const val SNAPSHOT_FLOOD_DETAIL =
            "The per-user snapshot flood limit is exhausted; refetch the batch after the advertised wait"
        const val CONNECTION_NOT_FOUND_DETAIL =
            "The connectionId is not a live registration of the caller (unknown or expired)"
        const val FLOOD_LIMIT_DETAIL =
            "The per-user heartbeat flood limit is exhausted; keep the stream and retry in the next interval"
        const val SETTINGS_MALFORMED_DETAIL = "incognito must be present and a boolean"
        const val SETTINGS_FLOOD_DETAIL =
            "The per-user presence settings flood limit is exhausted; repeat the toggle after the advertised wait"
    }
}
