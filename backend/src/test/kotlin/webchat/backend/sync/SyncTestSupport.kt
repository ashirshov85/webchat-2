package webchat.backend.sync

import org.assertj.core.api.Assertions.assertThat
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.util.UriComponentsBuilder
import webchat.backend.chats.MessagingTestSupport
import java.util.Optional
import java.util.UUID

/**
 * T002 (tasks.md Phase 1): shared fixtures of the 005 sync/delivery ITs —
 * used by T007 (DeliveryAckIT), T008 (SyncIT), T009
 * (AscendingHistoryIT), T030 (UnreadConvergenceIT) and T038
 * (BackpressureIT) — layered on top of the 004 [MessagingTestSupport]
 * (registration/login, №11 ensure, №16 send, №15/№17, SSE №18 over the
 * real 001 flows and Testcontainers PG 17 + Redis 7).
 *
 * Adds the 005-specific preparation and probe surface:
 *
 *  * a three-user fixture (`messagingTrio`) — the sync ITs need at least
 *    two parallel dialogs of one user (Alice–Bob and Alice–Carol) to
 *    assert the №26 chat ordering by last activity, plus a third peer for
 *    per-chat isolation, and DeliveryAckIT re-uses Carol as the stranger
 *    behind `403 not_participant`;
 *  * message seeding with CONTROLLED `seq` ([seedChatBacklog]): rows are
 *    inserted straight into the real tables in the exact send order — the
 *    write-path end state of №16 (data-model 004 §3, the HistoryIT
 *    precedent) — because a sync backlog of 50+ real sends would cross the
 *    FR-011 flood limit (30/minute per user) while the send path itself is
 *    already locked by the 004 ITs; `seq` is GENERATED ALWAYS AS IDENTITY,
 *    so the insertion order defines the ascending sequence (and, across
 *    chats, the relative `chats.last_seq` order №26 sorts by);
 *  * the three contract operations of 005 — №25 `delivery-ack`
 *    ([deliveryAck]), №26 `sync` ([requestSync]) and the №15 ascending
 *    page `?after=` ([listMessagesAfter]) — as RAW responses, so every IT
 *    keeps asserting status codes and problem+json bodies verbatim;
 *  * DB probes for the authoritative assertions: the delivery position
 *    `chat_participants.delivered_up_to_seq` ([deliveredUpToSeq] — moves
 *    ONLY by №25, so the ITs prove "sync answer / SSE frame never write
 *    it" against the table itself), the peer read watermark ([readUpToSeq]
 *    — ✓✓ convergence of T030), the chat head [lastSeqOf] (the
 *    `invalid_up_to_seq` boundary of T007) and the №14 deletion end state
 *    ([setDeletionWatermark] — the truncation branch of T008).
 *
 * The calls are issued through TestRestTemplate on purpose: tasks.md names
 * WebTestClient, but the build carries no webflux dependency (plan.md VII
 * adds none — the same deviation 004 recorded in RealtimeSseIT), and the
 * assertions here never need frame-level WHATWG access.
 *
 * NOTE (TDD, constitution VI): written BEFORE T003–T015 land — until the
 * contract 0.5.0 endpoints (T012–T015) and migration V12 (T005) exist,
 * every №25/№26/№15-`after` call answers 404 and every
 * `delivered_up_to_seq` probe fails (RED) by design.
 */
@Suppress("TooManyFunctions") // T002: fixture library — one helper per 005 probe/operation
abstract class SyncTestSupport : MessagingTestSupport() {
    @Autowired
    private lateinit var syncRestTemplate: TestRestTemplate

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    /**
     * One seeded backlog row with its CONTROLLED coordinates: the identity
     * [seq] in the send order, the client UUID id (the №16 dedup key /
     * public identifier) and the text verbatim — every IT assertion maps
     * rows to indices through these.
     */
    protected data class SeededMessage(
        val id: UUID,
        val seq: Long,
        val senderId: UUID,
        val text: String,
    )

    /**
     * The 005 user fixture: three fully registered and logged-in users —
     * Alice (the sync subject owning two dialogs), Bob and Carol (her two
     * peers; Carol doubles as the `403 not_participant` stranger for chats
     * she never joined). Every call is unique through the 004 registration
     * flow, so no test method shares accounts or rate-limit buckets.
     */
    protected fun messagingTrio(): Triple<MessagingUser, MessagingUser, MessagingUser> =
        Triple(
            messagingUser("alice"),
            messagingUser("bob"),
            messagingUser("carol"),
        )

    /** №11 happy path as a dialog between two fixture users → chatId. */
    protected fun ensureDialog(
        user: MessagingUser,
        peer: MessagingUser,
    ): UUID = ensureChatOk(user, peer.id)

    /**
     * Real №16 send → the assigned `seq` (the controlled coordinate of the
     * live path — for the few sends an IT issues under the 30/min flood
     * limit; bulk backlogs go through [seedChatBacklog] instead).
     */
    protected fun sendOkSeq(
        user: MessagingUser,
        chatId: UUID,
        text: String,
    ): Long = sendMessageOk(user, chatId, text)["seq"].asLong()

    /**
     * Seeds [count] messages straight into the real tables — the exact end
     * state of [count] №16 sends (data-model 004 §3, HistoryIT precedent):
     * rows with client UUID ids and the IDENTITY `seq`, plus the monotone
     * `chats.last_seq` cache refresh. [senderFor] maps every index to its
     * sender, so a fixture controls the incoming/outgoing mix (the
     * `sender_id != user` leg of the unread formula); texts are
     * `<textPrefix>-<index>` in send order. Returns the rows ASCENDING by
     * `seq` — the exact order №26 deltas and №15 `after` pages must replay.
     */
    protected fun seedChatBacklog(
        chatId: UUID,
        count: Int,
        senderFor: (index: Int) -> MessagingUser,
        textPrefix: String = DEFAULT_TEXT_PREFIX,
    ): List<SeededMessage> {
        val rows =
            (0 until count).map { index ->
                val sender = senderFor(index)
                val id = UUID.randomUUID()
                val text = "$textPrefix-$index"
                val inserted =
                    jdbcTemplate.update(
                        "INSERT INTO messages (id, chat_id, sender_id, text) VALUES (?, ?, ?, ?)",
                        id,
                        chatId,
                        sender.id,
                        text,
                    )
                assertThat(inserted)
                    .overridingErrorMessage("seeded message <%s> must insert exactly one row", text)
                    .isEqualTo(1)
                SeededMessage(id = id, seq = 0L, senderId = sender.id, text = text)
            }
        val updated =
            jdbcTemplate.update(
                """
                UPDATE chats
                SET last_seq = (SELECT COALESCE(MAX(seq), 0) FROM messages WHERE chat_id = ?)
                WHERE id = ?
                """.trimIndent(),
                chatId,
                chatId,
            )
        assertThat(updated)
            .overridingErrorMessage("the backlog must land on the chat row")
            .isEqualTo(1)
        val seqById =
            jdbcTemplate
                .query(
                    "SELECT id, seq FROM messages WHERE chat_id = ? AND id IN (${placeholders(rows.size)})",
                    { rs, _ -> UUID.fromString(rs.getString("id")) to rs.getLong("seq") },
                    chatId,
                    *rows.map { it.id }.toTypedArray(),
                ).toMap()
        return rows.map { row ->
            val seq =
                seqById[row.id]
                    ?: error("seeded message ${row.id} must resolve its seq")
            row.copy(seq = seq)
        }
    }

    /**
     * Contract №25 POST /api/v1/users/me/delivery-ack — raw response for
     * status/problem assertions. [acks] carries the batch as
     * `chatId to upToSeq` pairs (≤ 100 per the contract; the >100 refusal
     * of T007 builds a longer list the same way).
     */
    protected fun deliveryAck(
        user: MessagingUser,
        acks: List<Pair<UUID, Long>>,
    ): ResponseEntity<String> =
        postJsonAuthorized(
            DELIVERY_ACK_PATH,
            mapOf(
                "acks" to
                    acks.map { (chatId, upToSeq) ->
                        mapOf("chatId" to chatId.toString(), "upToSeq" to upToSeq)
                    },
            ),
            user,
        )

    /**
     * Contract №26 POST /api/v1/users/me/sync — raw response. [cursors] is
     * the client cursor map as `chatId to upToSeq` pairs (stranger/unknown
     * ids ride along verbatim — the silent-ignore rule distinguishes №26
     * from the atomic №25 batch); `chatLimit`/`messageLimit` map to the
     * optional 1..50 request knobs (default 20/50).
     */
    protected fun requestSync(
        user: MessagingUser,
        cursors: List<Pair<UUID, Long>>,
        chatLimit: Int? = null,
        messageLimit: Int? = null,
    ): ResponseEntity<String> {
        val payload =
            buildMap {
                put(
                    "cursors",
                    cursors.map { (chatId, upToSeq) ->
                        mapOf("chatId" to chatId.toString(), "upToSeq" to upToSeq)
                    },
                )
                chatLimit?.let { put("chatLimit", it) }
                messageLimit?.let { put("messageLimit", it) }
            }
        return postJsonAuthorized(SYNC_PATH, payload, user)
    }

    /**
     * Contract №15 GET /chats/{chatId}/messages?after=<seq> — raw response
     * of the 005 ascending page (exclusive `after`, `seq ASC`, the
     * `nextAfter` tail cursor); `after`+`before` mixing is asserted by
     * passing both — combine with the 004 [MessagingTestSupport]
     * `listMessages` helper for the `400 mixed_cursors` case.
     */
    protected fun listMessagesAfter(
        user: MessagingUser,
        chatId: UUID,
        after: Long,
        limit: Int? = null,
    ): ResponseEntity<String> {
        val path =
            UriComponentsBuilder
                .fromPath("/api/v1/chats/$chatId/messages")
                .queryParam("after", after)
                .queryParamIfPresent("limit", Optional.ofNullable(limit))
                .build()
                .toUriString()
        return syncRestTemplate.exchange(
            path,
            HttpMethod.GET,
            authorizedEntity(user),
            String::class.java,
        )
    }

    /**
     * DB probe: the user's delivery position in the chat
     * (`chat_participants.delivered_up_to_seq`, data-model 005 entity 1) —
     * `null` when no participant row exists. Read-only on purpose: the
     * position moves ONLY by the №25 ack, so the ITs prove the
     * "sync answer / SSE frame never write it" invariant against this
     * column (and advance it exclusively through [deliveryAck]).
     */
    protected fun deliveredUpToSeq(
        userId: UUID,
        chatId: UUID,
    ): Long? = participantColumn("delivered_up_to_seq", userId, chatId)

    /**
     * DB probe: the user's read watermark (`last_read_seq`, №17 GREATEST) —
     * the ✓✓ convergence point UnreadConvergenceIT (T030) asserts after
     * the offline pendingReads flush.
     */
    protected fun readUpToSeq(
        userId: UUID,
        chatId: UUID,
    ): Long? = participantColumn("last_read_seq", userId, chatId)

    /**
     * DB probe: the chat head `chats.last_seq` — the `invalid_up_to_seq`
     * boundary of DeliveryAckIT (T007: ack of `last_seq + 1` must be
     * refused) and the №26 ordering coordinate of SyncIT (T008).
     */
    protected fun lastSeqOf(chatId: UUID): Long =
        jdbcTemplate
            .queryForList("SELECT last_seq FROM chats WHERE id = ?", Long::class.java, chatId)
            .firstOrNull()
            ?: error("chat $chatId must resolve its last_seq")

    /**
     * The exact №14 DELETE end state for the deleting participant
     * (`deleted_up_to_seq` + `hidden`, the HistoryIT precedent): SyncIT
     * (T008) pins the truncation boundary at an ARBITRARY seq — lower than
     * the live head — to drive the `truncatedUpToSeq` branch of §5
     * (messages below the watermark are inaccessible, never unread).
     */
    protected fun setDeletionWatermark(
        userId: UUID,
        chatId: UUID,
        upToSeq: Long,
    ) {
        val updated =
            jdbcTemplate.update(
                """
                UPDATE chat_participants
                SET deleted_up_to_seq = ?, hidden = true
                WHERE chat_id = ? AND user_id = ?
                """.trimIndent(),
                upToSeq,
                chatId,
                userId,
            )
        assertThat(updated)
            .overridingErrorMessage("the deletion watermark must land on the participant row")
            .isEqualTo(1)
    }

    private fun participantColumn(
        column: String,
        userId: UUID,
        chatId: UUID,
    ): Long? =
        jdbcTemplate
            .queryForList(
                "SELECT $column FROM chat_participants WHERE chat_id = ? AND user_id = ?",
                Long::class.java,
                chatId,
                userId,
            ).firstOrNull()

    private fun postJsonAuthorized(
        path: String,
        payload: Map<String, Any>,
        user: MessagingUser,
    ): ResponseEntity<String> =
        syncRestTemplate.postForEntity(
            path,
            HttpEntity(payload, authorizedHeaders(user)),
            String::class.java,
        )

    private fun authorizedEntity(user: MessagingUser): HttpEntity<Void> = HttpEntity(null, authorizedHeaders(user))

    private fun authorizedHeaders(user: MessagingUser): HttpHeaders =
        HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            setBearerAuth(user.accessToken)
        }

    private companion object {
        const val DEFAULT_TEXT_PREFIX = "sync"
        const val DELIVERY_ACK_PATH = "/api/v1/users/me/delivery-ack"
        const val SYNC_PATH = "/api/v1/users/me/sync"

        fun placeholders(count: Int): String = List(count) { "?" }.joinToString(", ")
    }
}
