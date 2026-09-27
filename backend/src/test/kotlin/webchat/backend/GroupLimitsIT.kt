package webchat.backend

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import webchat.backend.sync.SyncTestSupport
import java.util.UUID

/** quickstart.md §2 «Новые настройки»: the stress profile lowers the 200 cap to 3 for this whole context. */
private const val LOWERED_MAX_MEMBERS = 3

/**
 * T018a (tasks.md Phase 3, US1, constitution VI Test-First) — the LIMITS
 * & IDEMPOTENCY slice of №27/№31 over the real 001 flows, Testcontainers
 * PG+Redis and real HTTP (no mocks), every method staging its own users
 * and contacts so nothing leaks between methods:
 *
 *  * capacity `409 group_full` (FR-002): the class runs under the LOWERED
 *    `groups.max-members=3` stress profile (quickstart.md §2 «Новые
 *    настройки», the BackpressureIT low-limit-context precedent) — №31
 *    filling the roster TO the cap succeeds, one more member refuses
 *    `409 group_full` (`errors: {group: [group_full]}`) and repeated
 *    attempts create no records (quickstart §3.1 step 4); №27 with an
 *    initial roster over the cap answers the SAME `409 group_full`
 *    (api-contract.md §2 note) and creates nothing;
 *  * FR-001 boundary ACCEPTANCE (the blank/65/257 refusals are pinned by
 *    T018): a title of exactly 64 chars padded with outer whitespace is
 *    accepted and STORED TRIMMED — the «1–64 after trim» rule (№29
 *    reuses the same rule; its PATCH refusals arrive with US4/T049) —
 *    and a 256-char description is accepted;
 *  * №27/№31 batch shape: a non-uuid element, a duplicate and a batch
 *    over 199 answer `400 invalid_member_ids`/`invalid_user_ids` (№31
 *    also refuses the empty batch — the 1–199 window) and the whole
 *    batch is refused ATOMICALLY: no group row appears (№27), the
 *    roster is unchanged (№31, FR-002);
 *  * №31 idempotency: re-adding an already-ACTIVE member answers 200
 *    with no roster duplicate and a single participant row.
 *
 * NOTE (TDD, constitution VI): written BEFORE the US1 implementation
 * tasks (T020–T022) — until they land, every №27/№31 call answers 404
 * (no controller mapping yet), so every method here fails (RED) by
 * design. The kick×leave concurrency edge of T061 EXTENDS this class
 * later — keep every fixture self-contained per method.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["groups.max-members=$LOWERED_MAX_MEMBERS"],
)
@Suppress("TooManyFunctions", "LargeClass") // T018a: one method per limits rule of tasks.md
class GroupLimitsIT(
    @Autowired private val restTemplate: TestRestTemplate,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) : SyncTestSupport() {
    /**
     * №27 capacity (api-contract.md §2 note): with the cap lowered to 3 an
     * initial roster of three contacts + creator exceeds it — the SAME
     * `409 group_full` as №31, and the refused request is atomic (no group
     * row for the creator, nobody became a member).
     */
    @Test
    fun `create beyond the configured capacity answers 409 group_full and creates nothing`() {
        val owner = messagingUser("owner")
        val (bob, carol, dave) = contactsOf(owner, "bob", "carol", "dave")

        val response =
            createGroup(
                owner,
                GROUP_TITLE,
                memberUserIds = listOf(bob.id, carol.id, dave.id).map(UUID::toString),
            )

        assertThat(response.statusCode)
            .overridingErrorMessage(
                "№27 with an initial roster over the lowered cap must answer 409, got <%s>: %s",
                response.statusCode,
                response.body,
            ).isEqualTo(HttpStatus.CONFLICT)
        assertProblem(response, GROUP_FIELD, GROUP_FULL)
        assertThat(groupCountOf(owner.id))
            .overridingErrorMessage("the refused №27 must be atomic — no group may exist for the creator")
            .isZero
        assertThat(groupCountOf(bob.id))
            .overridingErrorMessage("a contact named in the capacity-refused №27 must not become a member")
            .isZero
        assertThat(groupCountOf(carol.id)).isZero
        assertThat(groupCountOf(dave.id)).isZero
    }

    /**
     * №31 capacity (api-contract.md №31, quickstart §3.1 step 4): filling
     * the roster TO the lowered cap succeeds (the conflict is strictly
     * `active + batch > max`), one more member refuses `409 group_full`,
     * and repeated attempts create no records — the roster stays at the
     * cap and the overflowing candidate holds no participant row.
     */
    @Test
    fun `addMembers fills the roster to the configured cap and refuses beyond it`() {
        val owner = messagingUser("owner")
        val (bob, carol, dave) = contactsOf(owner, "bob", "carol", "dave")
        val chatId = chatIdOf(createGroupOk(owner, memberUserIds = listOf(bob.id.toString())))

        val fill = addGroupMembers(owner, chatId, listOf(carol.id.toString()))
        assertThat(fill.statusCode)
            .overridingErrorMessage(
                "№31 reaching exactly the cap (active 2 + batch 1 = 3) must succeed, got <%s>: %s",
                fill.statusCode,
                fill.body,
            ).isEqualTo(HttpStatus.OK)
        assertThat(activeMemberCount(chatId))
            .overridingErrorMessage("the filled roster must sit exactly at the lowered cap")
            .isEqualTo(LOWERED_MAX_MEMBERS.toLong())

        val overflow = addGroupMembers(owner, chatId, listOf(dave.id.toString()))
        assertThat(overflow.statusCode)
            .overridingErrorMessage(
                "№31 beyond the cap must answer 409 group_full, got <%s>: %s",
                overflow.statusCode,
                overflow.body,
            ).isEqualTo(HttpStatus.CONFLICT)
        assertProblem(overflow, GROUP_FIELD, GROUP_FULL)

        val retry = addGroupMembers(owner, chatId, listOf(dave.id.toString()))
        assertThat(retry.statusCode)
            .overridingErrorMessage("a repeated №31 over the cap must answer 409 again")
            .isEqualTo(HttpStatus.CONFLICT)
        assertProblem(retry, GROUP_FIELD, GROUP_FULL)

        assertThat(activeMemberCount(chatId))
            .overridingErrorMessage("repeated overflowing №31 attempts must not change the roster")
            .isEqualTo(LOWERED_MAX_MEMBERS.toLong())
        assertThat(participantRowsOf(chatId, dave.id))
            .overridingErrorMessage("the overflowing candidate must never gain a participant row")
            .isZero
    }

    /**
     * №27 boundary acceptance (FR-001, api-contract.md №27): a title of
     * exactly 64 chars padded with outer whitespace is accepted and stored
     * TRIMMED (both bounds apply to the value AFTER trim, the V14 CHECK
     * `btrim` semantics; №29 reuses the rule) and a 256-char description
     * sits exactly on its bound.
     */
    @Test
    fun `create accepts the boundary metadata and stores the title trimmed`() {
        val owner = messagingUser("owner")
        val paddedBoundaryTitle = "  $BOUNDARY_TITLE  "
        assertThat(paddedBoundaryTitle.trim().length)
            .overridingErrorMessage("the fixture title must trim to exactly the 64-char contract bound")
            .isEqualTo(TITLE_MAX_LENGTH)

        val response = createGroup(owner, paddedBoundaryTitle, description = BOUNDARY_DESCRIPTION)

        assertThat(response.statusCode)
            .overridingErrorMessage(
                "a title at the 64 bound after trim and a 256-char description must be accepted, got <%s>: %s",
                response.statusCode,
                response.body,
            ).isEqualTo(HttpStatus.CREATED)
        val view = objectMapper.readTree(response.body)
        assertThat(view["title"].asText())
            .overridingErrorMessage(
                "the stored title must be TRIMMED to its 64-char bound, got <%s>",
                view["title"].asText(),
            ).isEqualTo(BOUNDARY_TITLE)
        assertThat(view["description"].asText()).isEqualTo(BOUNDARY_DESCRIPTION)
    }

    /**
     * №27 batch shape (api-contract.md №27, FR-002): a non-uuid element, a
     * duplicate and a batch over 199 each answer `400 invalid_member_ids`
     * and the refusal is ATOMIC — even the valid contact riding in the
     * same batch never becomes a member and no group row appears.
     */
    @Test
    fun `create refuses non-uuid duplicate and oversized initial rosters atomically`() {
        val owner = messagingUser("owner")
        val bob = messagingUser("bob")
        addContact(owner, bob.id)

        val malformedRosters =
            listOf(
                "a non-uuid element" to listOf(bob.id.toString(), NOT_A_UUID),
                "a duplicate id" to listOf(bob.id.toString(), bob.id.toString()),
                "a batch over 199" to uniqueUuidBatch(OVERSIZED_BATCH),
            )
        for ((label, roster) in malformedRosters) {
            val response = createGroup(owner, GROUP_TITLE, memberUserIds = roster)
            assertThat(response.statusCode)
                .overridingErrorMessage(
                    "№27 with <%s> in memberUserIds must be refused 400, got <%s>: %s",
                    label,
                    response.statusCode,
                    response.body,
                ).isEqualTo(HttpStatus.BAD_REQUEST)
            assertProblem(response, MEMBER_USER_IDS_FIELD, INVALID_MEMBER_IDS)
        }

        assertThat(groupCountOf(owner.id))
            .overridingErrorMessage("every malformed №27 must be atomic — no group may exist for the creator")
            .isZero
        assertThat(groupCountOf(bob.id))
            .overridingErrorMessage("the valid contact inside the refused batches must not become a member")
            .isZero
    }

    /**
     * №31 batch shape (api-contract.md №31, FR-002): a non-uuid element, a
     * duplicate, the empty batch (the 1–199 window) and a batch over 199
     * each answer `400 invalid_user_ids` and the refusal is ATOMIC — the
     * roster is unchanged and the un-added contact holds no row.
     */
    @Test
    fun `addMembers refuses malformed batches atomically leaving the roster unchanged`() {
        val owner = messagingUser("owner")
        val (bob, carol) = contactsOf(owner, "bob", "carol")
        val chatId = chatIdOf(createGroupOk(owner, memberUserIds = listOf(bob.id.toString())))

        val malformedBatches =
            listOf(
                "a non-uuid element" to listOf(carol.id.toString(), NOT_A_UUID),
                "a duplicate id" to listOf(carol.id.toString(), carol.id.toString()),
                "an empty batch" to emptyList(),
                "a batch over 199" to uniqueUuidBatch(OVERSIZED_BATCH),
            )
        for ((label, batch) in malformedBatches) {
            val response = addGroupMembers(owner, chatId, batch)
            assertThat(response.statusCode)
                .overridingErrorMessage(
                    "№31 batch with <%s> must be refused 400, got <%s>: %s",
                    label,
                    response.statusCode,
                    response.body,
                ).isEqualTo(HttpStatus.BAD_REQUEST)
            assertProblem(response, USER_IDS_FIELD, INVALID_USER_IDS)
        }

        assertThat(activeMemberCount(chatId))
            .overridingErrorMessage("every malformed №31 batch must leave the roster unchanged")
            .isEqualTo(INITIAL_ROSTER_SIZE)
        assertThat(participantRowsOf(chatId, carol.id))
            .overridingErrorMessage("the un-added contact of the refused batches must hold no participant row")
            .isZero
    }

    /**
     * №31 idempotency (api-contract.md №31/§2 «Идемпотентность»): re-adding
     * an already-ACTIVE member answers 200 with the current roster — the
     * member appears exactly ONCE and holds a single participant row.
     */
    @Test
    fun `addMembers re-adding an active member answers 200 without a duplicate`() {
        val owner = messagingUser("owner")
        val (bob, carol) = contactsOf(owner, "bob", "carol")
        val chatId = chatIdOf(createGroupOk(owner, memberUserIds = listOf(bob.id.toString())))

        val response = addGroupMembers(owner, chatId, listOf(bob.id.toString()))

        assertThat(response.statusCode)
            .overridingErrorMessage(
                "re-adding an already-active member is idempotent and must answer 200, got <%s>: %s",
                response.statusCode,
                response.body,
            ).isEqualTo(HttpStatus.OK)
        val members = objectMapper.readTree(response.body)["members"].toList()
        assertThat(memberIdsOf(members))
            .overridingErrorMessage("the roster answer must carry the re-added member exactly once")
            .containsExactlyInAnyOrder(owner.id.toString(), bob.id.toString())
        assertThat(participantRowsOf(chatId, bob.id))
            .overridingErrorMessage("idempotent №31 must not duplicate the participant row")
            .isEqualTo(1L)
        assertThat(activeMemberCount(chatId))
            .overridingErrorMessage("idempotent №31 must not change the active roster size")
            .isEqualTo(INITIAL_ROSTER_SIZE)

        assertThat(participantRowsOf(chatId, carol.id))
            .overridingErrorMessage("the idempotent re-add fixture must not touch other users")
            .isZero
    }

    /** Registers [labels] and makes each a contact of the [owner] — the №27/№31 FR-002 source. */
    private fun contactsOf(
        owner: MessagingUser,
        vararg labels: String,
    ): List<MessagingUser> =
        labels
            .map { label -> messagingUser(label) }
            .onEach { contact -> addContact(owner, contact.id) }

    /** Contract №27 `POST /api/v1/groups` — raw response for status/error assertions. */
    private fun createGroup(
        owner: MessagingUser,
        title: String,
        description: String? = null,
        memberUserIds: List<String> = emptyList(),
    ): ResponseEntity<String> =
        postJsonAuthorized(
            GROUPS_PATH,
            buildMap {
                put(TITLE_FIELD, title)
                description?.let { put(DESCRIPTION_FIELD, it) }
                if (memberUserIds.isNotEmpty()) {
                    put(MEMBER_USER_IDS_FIELD, memberUserIds)
                }
            },
            owner,
        )

    /** №27 happy path → the stored `GroupView` body. */
    private fun createGroupOk(
        owner: MessagingUser,
        title: String = GROUP_TITLE,
        memberUserIds: List<String> = emptyList(),
    ): JsonNode {
        val response = createGroup(owner, title, memberUserIds = memberUserIds)
        assertThat(response.statusCode)
            .overridingErrorMessage("№27 create must answer 201, got <%s>: %s", response.statusCode, response.body)
            .isEqualTo(HttpStatus.CREATED)
        return objectMapper.readTree(response.body)
    }

    /**
     * Contract №31 `POST /api/v1/groups/{chatId}/members` — raw response;
     * the batch rides verbatim (malformed values included).
     */
    private fun addGroupMembers(
        user: MessagingUser,
        chatId: UUID,
        userIds: List<String>,
    ): ResponseEntity<String> =
        postJsonAuthorized(
            "$GROUPS_PATH/$chatId/members",
            mapOf(USER_IDS_FIELD to userIds),
            user,
        )

    /** Contract №21 `POST /api/v1/contacts` — fixture contact for the №27/№31 gates. */
    private fun addContact(
        user: MessagingUser,
        userId: UUID,
    ) {
        val response = postJsonAuthorized(CONTACTS_PATH, mapOf(USER_ID_FIELD to userId.toString()), user)
        assertThat(response.statusCode.is2xxSuccessful)
            .overridingErrorMessage(
                "fixture contact №21 of <%s> → <%s> must succeed, got <%s>: %s",
                user.username,
                userId,
                response.statusCode,
                response.body,
            ).isTrue
    }

    private fun postJsonAuthorized(
        path: String,
        payload: Map<String, Any>,
        user: MessagingUser,
    ): ResponseEntity<String> {
        val headers =
            HttpHeaders().apply {
                contentType = MediaType.APPLICATION_JSON
                setBearerAuth(user.accessToken)
            }
        return restTemplate.postForEntity(path, HttpEntity(payload, headers), String::class.java)
    }

    private fun chatIdOf(view: JsonNode): UUID = UUID.fromString(view["chatId"].asText())

    private fun memberIdsOf(members: List<JsonNode>): List<String> = members.map { it["user"]["id"].asText() }

    /** RFC 9457 refusal probe: `application/problem+json` with `errors.{field}=[code]` (convention 002). */
    private fun assertProblem(
        response: ResponseEntity<String>,
        field: String,
        code: String,
    ) {
        assertThat(response.headers.contentType?.toString())
            .overridingErrorMessage(
                "refusals must be RFC 9457 application/problem+json, got <%s>",
                response.headers.contentType,
            ).contains(PROBLEM_JSON_MEDIA_TYPE)
        val codes =
            objectMapper
                .readTree(response.body)["errors"]
                ?.get(field)
                ?.map { it.asText() }
                .orEmpty()
        assertThat(codes)
            .overridingErrorMessage(
                "the problem must carry errors.%s=[%s], got <%s> in %s",
                field,
                code,
                codes,
                response.body,
            ).containsExactly(code)
    }

    /** The number of `kind='group'` chats the user holds an active row in — the №27 atomicity probe. */
    private fun groupCountOf(userId: UUID): Long =
        jdbcTemplate.queryForObject(
            """
            SELECT count(*) FROM chats c
            JOIN chat_participants p ON p.chat_id = c.id
            WHERE p.user_id = ? AND c.kind = 'group'
            """.trimIndent(),
            Long::class.java,
            userId,
        ) ?: 0L

    /** The active roster size of the group — the «состав не меняется» probe of the atomic batches. */
    private fun activeMemberCount(chatId: UUID): Long =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM chat_participants WHERE chat_id = ? AND state = 'active'",
            Long::class.java,
            chatId,
        ) ?: 0L

    /** The participant row count of (chat, user) — the №31 idempotency/atomicity probe. */
    private fun participantRowsOf(
        chatId: UUID,
        userId: UUID,
    ): Long =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM chat_participants WHERE chat_id = ? AND user_id = ?",
            Long::class.java,
            chatId,
            userId,
        ) ?: 0L

    private companion object {
        const val GROUPS_PATH = "/api/v1/groups"
        const val CONTACTS_PATH = "/api/v1/contacts"

        const val PROBLEM_JSON_MEDIA_TYPE = "application/problem+json"

        const val GROUP_TITLE = "Limits IT group"

        /** api-contract.md header constants: title 1–64 after trim, description ≤256, batch 1–199. */
        const val TITLE_MAX_LENGTH = 64
        const val DESCRIPTION_MAX_LENGTH = 256
        const val MAX_MEMBER_BATCH = 199
        const val OVERSIZED_BATCH = MAX_MEMBER_BATCH + 1
        const val INITIAL_ROSTER_SIZE = 2L
        const val NOT_A_UUID = "definitely-not-a-uuid"

        const val TITLE_FIELD = "title"
        const val DESCRIPTION_FIELD = "description"
        const val MEMBER_USER_IDS_FIELD = "memberUserIds"
        const val USER_IDS_FIELD = "userIds"
        const val USER_ID_FIELD = "userId"
        const val GROUP_FIELD = "group"

        const val INVALID_MEMBER_IDS = "invalid_member_ids"
        const val INVALID_USER_IDS = "invalid_user_ids"
        const val GROUP_FULL = "group_full"

        /** FR-001 boundary fixtures: exactly the accepted caps (the refusals are T018's). */
        val BOUNDARY_TITLE = "g".repeat(TITLE_MAX_LENGTH)
        val BOUNDARY_DESCRIPTION = "d".repeat(DESCRIPTION_MAX_LENGTH)

        /** [size] fresh unique uuids — shape-valid elements for the over-bound batches. */
        fun uniqueUuidBatch(size: Int): List<String> = List(size) { UUID.randomUUID().toString() }
    }
}
