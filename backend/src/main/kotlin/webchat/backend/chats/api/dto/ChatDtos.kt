package webchat.backend.chats.api.dto

import com.fasterxml.jackson.annotation.JsonInclude
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
 *
 * 008a (api-contract.md §2, T015): the OPTIONAL [displayName] — the
 * peer's profile display name of FR-001 riding every `PublicUser`
 * `$ref` surface (№11/№12/№13 peer, №26 sync delta peer). NULL = «not
 * set» renders the field ABSENT (backward compatibility: 008 clients
 * fall back to `username`, SC-007); the caller's PERSONAL alias toward
 * the peer is NOT part of this object — it renders as the separate
 * additive [ChatView.peerAlias]/[ChatListItemView.peerAlias] slot of
 * the №11/№12/№13 bodies (the anyOf-additivity of openapi 0.9.0).
 */
data class ChatPeerView(
    val id: UUID,
    val username: String,
    val email: String,
    val status: String,
    val createdAt: Instant,
    @JsonInclude(JsonInclude.Include.NON_NULL) val displayName: String? = null,
)

/**
 * Contract №11/№13 success body — `ChatView` (openapi.yaml 0.6.0): the
 * resolved dialog, the peer projection and the US4 read watermarks —
 * or the GROUP variant of 006 (№13, T024, api-contract.md §3), type-
 * discriminated like [ChatListItemView].
 *
 * The DIRECT variant keeps the 0.4.0 shape verbatim: the group-only
 * fields stay ABSENT (NON_NULL — «direct-ответ — без изменений»,
 * backward-friendly), [peer]/[blockedByMe]/[peerReadUpToSeq] keep their
 * 004 meaning — `peerReadUpToSeq`/`myReadUpToSeq` (T043) carry the
 * FR-010 per-user marks of both sides (the sender renders ✓✓ from
 * `peerReadUpToSeq`); `blockedByMe` (T054, FR-020) is the ONLY block
 * projection in the API: the caller blocks the peer — the inverse «who
 * blocked me» field is deliberately absent, the blocked user learns
 * about the block ONLY from the `403 you_are_blocked` of his own send
 * (research.md 004 §6).
 *
 * The GROUP variant answers `type:'group'` with [title]/[description]
 * and the roster projection: [myRole] of the caller, [memberCount] of
 * the ACTIVE roster (1–200) and the ✓✓ rule of FR-012 —
 * [othersReadUpToSeq] is `MAX(last_read_seq)` of the active members
 * EXCEPT the caller (✓✓ once at least one other member has read; 0 in
 * a group of one — ✓✓ is never set), while
 * [myReadUpToSeq] stays the caller's own mark. The required-nullable
 * peer fields render as EXPLICIT `null`s: blocks never apply to groups
 * (Assumptions 006) and `peerReadUpToSeq` is REPLACED by
 * `othersReadUpToSeq` (openapi.yaml 0.6.0).
 *
 * 008a (api-contract.md §2, T015): the OPTIONAL [peerAlias] — the
 * caller's PERSONAL alias toward the direct peer (FR-003), joined via
 * `ContactRepository.aliasesOf` and visible to the caller ONLY (the
 * peer and third parties never receive it). NULL/ABSENT = «not set or
 * the peer is not a contact of the caller» → the client renders the
 * chain `displayName`/`username`; stays ABSENT on the GROUP variant
 * (NON_NULL — a group has no peer, the same 006 convention).
 */
data class ChatView(
    val chatId: UUID,
    @JsonInclude(JsonInclude.Include.NON_NULL) val type: String? = null,
    @JsonInclude(JsonInclude.Include.NON_NULL) val title: String? = null,
    @JsonInclude(JsonInclude.Include.NON_NULL) val description: String? = null,
    @JsonInclude(JsonInclude.Include.NON_NULL) val myRole: String? = null,
    @JsonInclude(JsonInclude.Include.NON_NULL) val othersReadUpToSeq: Long? = null,
    @JsonInclude(JsonInclude.Include.NON_NULL) val memberCount: Long? = null,
    val peer: ChatPeerView? = null,
    val blockedByMe: Boolean? = null,
    val peerReadUpToSeq: Long? = null,
    val myReadUpToSeq: Long = 0,
    @JsonInclude(JsonInclude.Include.NON_NULL) val peerAlias: String? = null,
)

/**
 * Contract №12 success item — `ChatListItem` (openapi.yaml 0.6.0): the
 * UNIFIED «Чаты» element (006, FR-014) — a direct dialog or a group,
 * discriminated by [type]. Direct rows keep the 0.4.0 shape verbatim:
 * the peer projection, the LAST VISIBLE message (`null` for a chat
 * without visible messages — empty or fully deleted for the caller)
 * with its FULL text (the ≤64-char cut is a client render), the unread
 * badge count (exact number; «99+» is the client render) and the
 * caller's own block mark; the group-only fields stay ABSENT (NON_NULL
 * — «direct-элементы могут не нести поле», backward-friendly,
 * api-contract.md 006 §3).
 *
 * Group rows carry the 006 projection: `type:'group'`, [title],
 * [memberCount] (the active roster, 1–200) and [myRole], with the peer
 * projection rendered as an EXPLICIT `null` — [peer] and
 * [blockedByMe] are required nullable fields of the schema, so they are
 * always present in the payload.
 *
 * `blockedByMe` (T054/T055, FR-020) is the ONLY block projection of the
 * list, exactly as in [ChatView]: no inverse «who blocked me» field —
 * the blocked user learns about the block ONLY from the `403
 * you_are_blocked` of his own send (research.md 004 §6). For groups it
 * is `null` — blocks never apply to groups (Assumptions 006).
 *
 * 008a (api-contract.md §2, T015): the OPTIONAL [peerAlias] — the
 * caller's PERSONAL alias toward the direct peer (FR-003), the №12
 * counterpart of [ChatView.peerAlias]: ONE batched
 * `ContactRepository.aliasesOf` read serves the whole panel. NULL/ABSENT
 * = «not set or the peer is not a contact» → the client renders the
 * chain `displayName`/`username`; stays ABSENT on GROUP rows (NON_NULL
 * — a group element has no peer, the same 006 convention).
 */
data class ChatListItemView(
    val chatId: UUID,
    @JsonInclude(JsonInclude.Include.NON_NULL) val type: String? = null,
    @JsonInclude(JsonInclude.Include.NON_NULL) val title: String? = null,
    @JsonInclude(JsonInclude.Include.NON_NULL) val memberCount: Long? = null,
    @JsonInclude(JsonInclude.Include.NON_NULL) val myRole: String? = null,
    val peer: ChatPeerView?,
    val lastMessage: MessageView?,
    val unreadCount: Long,
    val blockedByMe: Boolean?,
    @JsonInclude(JsonInclude.Include.NON_NULL) val peerAlias: String? = null,
)

/**
 * Contract №12 success body — the inline `{chats: [ChatListItem]}` object
 * of openapi.yaml 0.4.0: the caller's dialogs sorted by the last visible
 * message (server-side, FR-014); an EMPTY array is a valid answer (a user
 * without dialogs), so the field is always rendered.
 */
data class ChatsResponse(
    val chats: List<ChatListItemView>,
)
