package webchat.backend.chats.api.dto

import com.fasterxml.jackson.annotation.JsonInclude
import java.time.Instant
import java.util.UUID

/**
 * Contract №16 request body — `SendMessageRequest` (api-contract.md §4,
 * openapi.yaml 0.4.0): exactly `{clientMessageId: uuid, text: string}`.
 *
 * Both values arrive as raw strings on purpose: an absent or malformed
 * `clientMessageId` is rejected HERE as the contract 400
 * `errors: {clientMessageId: [invalid_uuid]}` and an absent `text` as
 * `errors: {text: [text_blank]}` (problem+json), instead of dying in
 * deserialization — the reply stays a typed problem, never the default
 * error page (the same convention as [EnsureChatRequest]).
 */
data class SendMessageRequest(
    val clientMessageId: String? = null,
    val text: String? = null,
)

/**
 * Contract №16/№15 success body — the `Message` schema (openapi.yaml
 * 0.4.0): `id` IS the client-supplied `clientMessageId` (FR-004 — the
 * public identifier and deduplication key), `seq` is the server-side
 * per-chat order cursor, `text` the FR-003-normalized value with internal
 * whitespace intact, `createdAt` the durable record moment («доставлено»,
 * FR-005). The SAME shape serves the №16 answer, the №15 pages and the
 * `message.created` realtime payload (one schema, US6).
 */
data class MessageView(
    val id: UUID,
    val chatId: UUID,
    val senderId: UUID,
    val text: String,
    val seq: Long,
    val createdAt: Instant,
)

/**
 * Contract №17 request body — `ReadRequest` (api-contract.md §4,
 * openapi.yaml 0.4.0): exactly `{upToSeq: int64}` — «прочитано до seq
 * включительно», bound `1 ≤ upToSeq ≤ seq последнего сообщения диалога`.
 *
 * The value arrives as a nullable raw on purpose: an ABSENT `upToSeq` is
 * rejected HERE as the contract 400 `errors: {upToSeq:
 * [invalid_up_to_seq]}` (below the minimum of the FR-010 bound) instead
 * of dying in deserialization — the reply stays a typed problem, never
 * the default error page (the same convention as [SendMessageRequest]).
 */
data class ReadRequest(
    val upToSeq: Long? = null,
)

/**
 * Contract №15 success body — `MessagePage` (openapi.yaml; 0.5.0
 * additive): [messages] ordered `seq DESC` (the `before`/default regime
 * of 004) with the EXCLUSIVE cursor [nextBefore] — the `seq` of the
 * oldest row of THIS page, ABSENT at exhaustion (an empty boundary page
 * is a correct answer, US3-4; FR-008).
 *
 * 005 §2 (T015, additive-optional): the ascending `after` regime fills
 * the mirror cursor [nextAfter] instead — the `seq` of the LAST row of
 * the page, ABSENT when nothing newer exists (the catch-up loop of
 * sync-protocol.md §4 stops on the missing cursor, never on an error).
 * The cursors are mode-specific and NEVER coexist in one answer — hence
 * the NON_NULL inclusion: each cursor is omitted from the payload of the
 * other regime, never rendered as an explicit `null`.
 */
data class MessagePageView(
    val messages: List<MessageView>,
    @JsonInclude(JsonInclude.Include.NON_NULL) val nextBefore: Long? = null,
    @JsonInclude(JsonInclude.Include.NON_NULL) val nextAfter: Long? = null,
)
