package webchat.backend.chats.domain.port

import com.fasterxml.jackson.databind.node.ObjectNode
import webchat.backend.chats.domain.model.Message
import java.util.UUID

/**
 * T016 (009, research §8): the SYSTEM-message append port of the `chats`
 * domain — the seam another domain uses to post a widget card into a chat
 * history (the first and so far only caller: the №44 conference create of
 * `conferences`, FR-002). The chats core stays widget-agnostic (constitution
 * IV/OCP): a new widget is a new `payload.type` composed by its own domain,
 * never a change here.
 *
 * Contract of [append] (data-model 009 §Сущность 4):
 *  * runs INSIDE the ambient operation transaction of the caller — the
 *    INSERT, the `seq` allocation and the `chats.last_seq`/`hidden`
 *    side effects commit or roll back TOGETHER with the triggering
 *    operation (a rolled-back conference create leaves NO card);
 *  * the `message.created` fan-out to the chat's ACTIVE roster fires
 *    strictly POST-COMMIT over the standard at-most-once channel (the
 *    004/006 delivery discipline, unchanged by 009 — a lost frame
 *    converges via the №15/№26 catch-up, FR-009);
 *  * the widget [payload] must be a JSON object serialized to at most
 *    [webchat.backend.chats.domain.model.Message.MAX_PAYLOAD_BYTES] bytes
 *    (the service validation of data-model §Сущность 4); [caption] is the
 *    FR-003-normalized placeholder/heading of the card (the DB `text`).
 */
fun interface SystemMessagePort {
    /**
     * Appends the widget row and returns the stored record (with its
     * allocated `seq`). The addressee snapshot — the chat's ACTIVE
     * participants, INCLUDING the operation actor (the card must land in
     * the creator's own history window too; the №44 answer carries no
     * message) — is read inside the same transaction; a system row has no
     * sender, so the FR-020 blocking pair and every sender-based gate
     * deliberately do NOT apply (the server is the author).
     */
    fun append(
        chatId: UUID,
        caption: String,
        payload: ObjectNode,
    ): Message
}
