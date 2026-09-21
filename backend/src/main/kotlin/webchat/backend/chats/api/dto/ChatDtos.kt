package webchat.backend.chats.api.dto

import java.time.Instant
import java.util.UUID

/**
 * Contract №11 request body — `EnsureChatRequest` (api-contract.md §4,
 * openapi.yaml 0.4.0): exactly `{peerUserId: uuid}`.
 *
 * The id arrives as a raw string on purpose: a malformed or absent value is
 * rejected HERE as the contract 400 `errors: {peerUserId: [invalid_uuid]}`
 * (problem+json), instead of dying in deserialization — the reply stays a
 * typed problem, never the default error page.
 */
data class EnsureChatRequest(
    val peerUserId: String? = null,
)

/**
 * The `peer: PublicUser` fragment of [ChatView] — the reused contract
 * schema `PublicUser {id, username, email, status, createdAt}`; `status`
 * carries the lowercase `user_status` enum, no password material leaves
 * the service (SC-005).
 */
data class ChatPeerView(
    val id: UUID,
    val username: String,
    val email: String,
    val status: String,
    val createdAt: Instant,
)

/**
 * Contract №11/№13 success body — `ChatView` (openapi.yaml 0.4.0): the
 * resolved dialog plus the peer projection.
 *
 * The read watermarks `peerReadUpToSeq`/`myReadUpToSeq` land with User
 * Story 4 (T043) and `blockedByMe` with User Story 5 (T054) — each story
 * extends this view independently without touching the pair resolve.
 */
data class ChatView(
    val chatId: UUID,
    val peer: ChatPeerView,
)
