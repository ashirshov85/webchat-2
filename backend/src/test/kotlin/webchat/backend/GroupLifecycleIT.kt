package webchat.backend

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import webchat.backend.sync.SyncTestSupport
import java.util.UUID

/**
 * T018 (tasks.md Phase 3, US1, constitution VI Test-First) — the CREATION
 * SLICE of the group lifecycle over the real 001 flows, Testcontainers
 * PG+Redis and real HTTP (no mocks), every method staging its own users and
 * contacts so nothing leaks between methods:
 *
 *  * №27 `POST /api/v1/groups` — 201 `GroupView`: the creator is the one
 *    `owner`, the initial roster members (from the creator's contacts) are
 *    `member` (FR-001/FR-002);
 *  * №28 `GET /api/v1/groups/{chatId}` — the `GroupView` for every active
 *    member and ONE uniform `404 group_not_found` for a non-member, a
 *    nonexistent group and the chatId of a DIRECT chat — existence is never
 *    disclosed (FR-008/FR-009, api-contract.md §1);
 *  * №12 `GET /chats` — the unified list carries the group element for the
 *    creator AND the added members: `type:'group'`, `title`, `memberCount`,
 *    `myRole`, `peer`/`blockedByMe` = null (api-contract.md §3, FR-014);
 *  * №13 `GET /chats/{chatId}` — the group variant of `ChatView` (title/
 *    description/myRole/memberCount/othersReadUpToSeq, peer fields null);
 *  * refusals: `invalid_title` (blank / 65 chars), `invalid_description`
 *    (257), `not_in_contacts` (the WHOLE №27 request is refused atomically
 *    — no group row appears), `self_forbidden` (№27 creator in the initial
 *    roster; №31 the adder adding himself), №31 by a plain member →
 *    `403 forbidden_role`, №31 by a non-member → `404 group_not_found`;
 *  * №31 `POST /groups/{chatId}/members` — the first addition initializes
 *    BOTH watermarks at the chat head, so the added member's №12 badge is 0
 *    (FR-013) even over pre-existing history.
 *
 * NOTE (TDD, constitution VI): written BEFORE the US1 implementation tasks
 * (T020–T024) — until they land, every №27/№28/№31 call answers 404 and the
 * group projections of №12/№13 stay direct-only, so every method here fails
 * (RED) by design. Limits (200/64/256/batch) and the №31 idempotency matrix
 * are owned by T018a (GroupLimitsIT); the roles/metadata/leave-delete slices
 * of this lifecycle (T040/T049/T059) EXTEND this class later — keep every
 * fixture self-contained per method.
 */
@Suppress("TooManyFunctions", "LargeClass") // T018: one method per US1 rule of tasks.md
class GroupLifecycleIT(
    @Autowired private val restTemplate: TestRestTemplate,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) : SyncTestSupport() {
    /** №27 happy path: 201 GroupView — creator owner, added contacts member (US1-1). */
    @Test
    fun `create answers 201 GroupView with the creator owner and the added contacts member`() {
        val owner = messagingUser("owner")
        val (bob, carol) = messagingUser("bob") to messagingUser("carol")
        addContact(owner, bob.id)
        addContact(owner, carol.id)

        val response = createGroup(owner, GROUP_TITLE, GROUP_DESCRIPTION, listOf(bob.id, carol.id))

        assertThat(response.statusCode)
            .overridingErrorMessage("№27 create must answer 201, got <%s>: %s", response.statusCode, response.body)
            .isEqualTo(HttpStatus.CREATED)
        val view = objectMapper.readTree(response.body)
        assertThat(fieldNames(view))
            .overridingErrorMessage("GroupView must carry exactly the contract fields, got <%s>", fieldNames(view))
            .containsExactlyInAnyOrderElementsOf(GROUP_VIEW_FIELDS)
        assertThat(view["title"].asText()).isEqualTo(GROUP_TITLE)
        assertThat(view["description"].asText()).isEqualTo(GROUP_DESCRIPTION)
        assertThat(view["myRole"].asText()).isEqualTo("owner")

        val members = view["members"].toList()
        assertThat(members)
            .overridingErrorMessage("the initial roster is the creator plus both contacts")
            .hasSize(3)
        members.forEach { member ->
            assertThat(fieldNames(member))
                .overridingErrorMessage(
                    "GroupMember must carry exactly the contract fields, got <%s>",
                    fieldNames(member),
                ).containsExactlyInAnyOrderElementsOf(GROUP_MEMBER_FIELDS)
            assertThat(fieldNames(member["user"]))
                .overridingErrorMessage("the member fragment must reuse the PublicUser schema")
                .containsExactlyInAnyOrderElementsOf(PUBLIC_USER_FIELDS)
            assertThat(member["joinedAt"].asText())
                .overridingErrorMessage("joinedAt is required (the FIRST addition moment)")
                .isNotEmpty
        }
        assertThat(roleOf(members, owner.id)).isEqualTo("owner")
        assertThat(roleOf(members, bob.id)).isEqualTo("member")
        assertThat(roleOf(members, carol.id)).isEqualTo("member")
    }

    /**
     * №28 privacy floor of US1 (FR-008/FR-009, api-contract.md §1): an
     * active member reads the GroupView with HIS role; a non-member, a
     * nonexistent chatId and the chatId of a DIRECT chat all get the SAME
     * `404 group_not_found` — no existence disclosure.
     */
    @Test
    fun `get serves the GroupView to members and one uniform 404 to everyone else`() {
        val owner = messagingUser("owner")
        val bob = messagingUser("bob")
        val stranger = messagingUser("erin")
        addContact(owner, bob.id)
        val chatId = chatIdOf(createGroupOk(owner, GROUP_TITLE, memberUserIds = listOf(bob.id)))

        val memberView = getGroup(bob, chatId)
        assertThat(memberView.statusCode)
            .overridingErrorMessage(
                "an active member must read №28, got <%s>: %s",
                memberView.statusCode,
                memberView.body,
            ).isEqualTo(HttpStatus.OK)
        assertThat(objectMapper.readTree(memberView.body)["myRole"].asText()).isEqualTo("member")
        assertThat(getGroup(owner, chatId).statusCode).isEqualTo(HttpStatus.OK)

        assertGroupNotFound(getGroup(stranger, chatId))
        assertGroupNotFound(getGroup(owner, UUID.randomUUID()))

        val directChatId = ensureChatOk(owner, stranger.id)
        assertGroupNotFound(getGroup(owner, directChatId))
    }

    /**
     * №12 group element (api-contract.md §3, FR-014): the unified «Чаты»
     * list carries the group for the creator AND every added member with
     * `type:'group'`/`title`/`memberCount`/`myRole` and the peer projection
     * nulled (`peer`/`blockedByMe` — block marks never apply to groups).
     */
    @Test
    fun `the chats list carries the group element for the creator and the added members`() {
        val owner = messagingUser("owner")
        val (bob, carol) = messagingUser("bob") to messagingUser("carol")
        addContact(owner, bob.id)
        addContact(owner, carol.id)
        val chatId = chatIdOf(createGroupOk(owner, GROUP_TITLE, GROUP_DESCRIPTION, listOf(bob.id, carol.id)))

        val ownerItem = groupListItemOf(owner, chatId)
        assertThat(fieldNames(ownerItem))
            .overridingErrorMessage(
                "the group ChatListItem must carry exactly the contract fields, got <%s>",
                fieldNames(ownerItem),
            ).containsExactlyInAnyOrderElementsOf(GROUP_CHAT_LIST_ITEM_FIELDS)
        assertThat(ownerItem["type"].asText()).isEqualTo("group")
        assertThat(ownerItem["title"].asText()).isEqualTo(GROUP_TITLE)
        assertThat(ownerItem["memberCount"].asLong()).isEqualTo(3)
        assertThat(ownerItem["myRole"].asText()).isEqualTo("owner")
        assertThat(ownerItem["unreadCount"].asLong())
            .overridingErrorMessage("a fresh group carries no unread messages")
            .isZero
        assertNullField(ownerItem, "peer")
        assertNullField(ownerItem, "blockedByMe")
        assertNullField(ownerItem, "lastMessage")

        val bobItem = groupListItemOf(bob, chatId)
        assertThat(bobItem["type"].asText()).isEqualTo("group")
        assertThat(bobItem["myRole"].asText()).isEqualTo("member")
        assertThat(bobItem["memberCount"].asLong()).isEqualTo(3)

        val carolItem = groupListItemOf(carol, chatId)
        assertThat(carolItem["myRole"].asText()).isEqualTo("member")
    }

    /**
     * №13 group variant (api-contract.md §3): `GET /chats/{chatId}` of a
     * group answers type/title/description/myRole/othersReadUpToSeq/
     * memberCount with the peer fields nulled — the direct variant stays
     * untouched (type discrimination).
     */
    @Test
    fun `the chat view of a group answers the group variant with the peer fields null`() {
        val owner = messagingUser("owner")
        val (bob, carol) = messagingUser("bob") to messagingUser("carol")
        addContact(owner, bob.id)
        addContact(owner, carol.id)
        val chatId = chatIdOf(createGroupOk(owner, GROUP_TITLE, GROUP_DESCRIPTION, listOf(bob.id, carol.id)))

        val response = getChat(owner, chatId)
        assertThat(response.statusCode)
            .overridingErrorMessage("№13 of a group must answer 200, got <%s>: %s", response.statusCode, response.body)
            .isEqualTo(HttpStatus.OK)
        val view = objectMapper.readTree(response.body)
        assertThat(fieldNames(view))
            .overridingErrorMessage(
                "the group ChatView must carry exactly the contract fields, got <%s>",
                fieldNames(view),
            ).containsExactlyInAnyOrderElementsOf(GROUP_CHAT_VIEW_FIELDS)
        assertThat(view["chatId"].asText()).isEqualTo(chatId.toString())
        assertThat(view["type"].asText()).isEqualTo("group")
        assertThat(view["title"].asText()).isEqualTo(GROUP_TITLE)
        assertThat(view["description"].asText()).isEqualTo(GROUP_DESCRIPTION)
        assertThat(view["myRole"].asText()).isEqualTo("owner")
        assertThat(view["memberCount"].asLong()).isEqualTo(3)
        assertThat(view["othersReadUpToSeq"].asLong())
            .overridingErrorMessage("a fresh group has no read marks yet")
            .isZero
        assertThat(view["myReadUpToSeq"].asLong()).isZero
        assertNullField(view, "peer")
        assertNullField(view, "blockedByMe")
        assertNullField(view, "peerReadUpToSeq")

        val bobView = objectMapper.readTree(getChat(bob, chatId).body)
        assertThat(bobView["type"].asText()).isEqualTo("group")
        assertThat(bobView["myRole"].asText()).isEqualTo("member")
    }

    /**
     * №27 validation (FR-001, api-contract.md №27): a blank title (empty or
     * whitespace-only — the trim rule) and a 65-char title are refused
     * `400 invalid_title`; a 257-char description is refused
     * `400 invalid_description`.
     */
    @Test
    fun `create refuses blank and oversized titles and an oversized description`() {
        val owner = messagingUser("owner")

        for (invalidTitle in listOf("", BLANK_TITLE, "x".repeat(TITLE_TOO_LONG))) {
            val response = createGroup(owner, invalidTitle)
            assertThat(response.statusCode)
                .overridingErrorMessage(
                    "title <%s…> of length ${invalidTitle.length} must be refused 400, got <%s>: %s",
                    invalidTitle.take(QUOTE_CONTEXT_LENGTH),
                    response.statusCode,
                    response.body,
                ).isEqualTo(HttpStatus.BAD_REQUEST)
            assertProblem(response, TITLE_FIELD, INVALID_TITLE)
        }

        val oversizedDescription = createGroup(owner, GROUP_TITLE, "d".repeat(DESCRIPTION_TOO_LONG))
        assertThat(oversizedDescription.statusCode)
            .overridingErrorMessage(
                "a ${DESCRIPTION_TOO_LONG}-char description must be refused 400, got <%s>: %s",
                oversizedDescription.statusCode,
                oversizedDescription.body,
            ).isEqualTo(HttpStatus.BAD_REQUEST)
        assertProblem(oversizedDescription, DESCRIPTION_FIELD, INVALID_DESCRIPTION)
    }

    /**
     * №27 contact gate + atomicity (FR-002, api-contract.md №27): anyone in
     * `memberUserIds` outside the creator's contacts refuses the WHOLE
     * request `422 not_in_contacts` — no group row survives for the
     * creator (a valid contact inside the same batch does not save it).
     */
    @Test
    fun `create refuses a non-contact member atomically and creates nothing`() {
        val owner = messagingUser("owner")
        val bob = messagingUser("bob")
        val stranger = messagingUser("erin")
        addContact(owner, bob.id)

        val response = createGroup(owner, GROUP_TITLE, memberUserIds = listOf(bob.id, stranger.id))

        assertThat(response.statusCode)
            .overridingErrorMessage(
                "a non-contact member must refuse №27 with 422, got <%s>: %s",
                response.statusCode,
                response.body,
            ).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
        assertProblem(response, MEMBER_USER_IDS_FIELD, NOT_IN_CONTACTS)
        assertThat(groupCountOf(owner.id))
            .overridingErrorMessage("the refused №27 must be atomic — no group may exist for the creator")
            .isZero
        assertThat(groupCountOf(bob.id))
            .overridingErrorMessage("the contact named in the refused batch must not become a member either")
            .isZero
    }

    /** №27 self-gate: the creator inside `memberUserIds` → `400 self_forbidden` (api-contract.md №27). */
    @Test
    fun `create refuses the creator inside the initial roster`() {
        val owner = messagingUser("owner")

        val response = createGroup(owner, GROUP_TITLE, memberUserIds = listOf(owner.id))

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertProblem(response, MEMBER_USER_IDS_FIELD, SELF_FORBIDDEN)
    }

    /**
     * №31 happy path + FR-013: the first addition initializes BOTH
     * watermarks of the added member at the chat head (`chats.last_seq`), so
     * over pre-existing history his №12 badge starts at 0 and the roster
     * answer carries the current active composition with roles.
     */
    @Test
    fun `addMembers initializes the first addition at the chat head with a zero badge`() {
        val owner = messagingUser("owner")
        val (bob, carol) = messagingUser("bob") to messagingUser("carol")
        addContact(owner, bob.id)
        addContact(owner, carol.id)
        val chatId = chatIdOf(createGroupOk(owner, GROUP_TITLE, memberUserIds = listOf(bob.id)))
        val history = seedChatBacklog(chatId, HISTORY_MESSAGES, senderFor = { owner })
        val chatHead = history.maxOf { it.seq }
        assertThat(chatHead)
            .overridingErrorMessage("the fixture must seed history above the zero head")
            .isPositive

        val response = addGroupMembers(owner, chatId, listOf(carol.id))

        assertThat(response.statusCode)
            .overridingErrorMessage(
                "№31 add from the owner must answer 200, got <%s>: %s",
                response.statusCode,
                response.body,
            ).isEqualTo(HttpStatus.OK)
        val body = objectMapper.readTree(response.body)
        assertThat(fieldNames(body))
            .overridingErrorMessage(
                "the №31 answer must carry exactly the members envelope, got <%s>",
                fieldNames(body),
            ).containsExactlyInAnyOrderElementsOf(listOf("members"))
        val members = body["members"].toList()
        assertThat(members.map { it["user"]["id"].asText() })
            .containsExactlyInAnyOrder(owner.id.toString(), bob.id.toString(), carol.id.toString())
        assertThat(roleOf(members, owner.id)).isEqualTo("owner")
        assertThat(roleOf(members, carol.id)).isEqualTo("member")

        assertThat(readUpToSeq(carol.id, chatId))
            .overridingErrorMessage("the FIRST addition must initialize last_read_seq at the chat head (FR-013)")
            .isEqualTo(chatHead)
        assertThat(deliveredUpToSeq(carol.id, chatId))
            .overridingErrorMessage("the FIRST addition must initialize delivered_up_to_seq at the chat head (FR-013)")
            .isEqualTo(chatHead)
        assertThat(groupListItemOf(carol, chatId)["unreadCount"].asLong())
            .overridingErrorMessage(
                "the added member's №12 badge must start at 0 over the pre-existing history (FR-013)",
            ).isZero
    }

    /**
     * №31 refusals of US1 (api-contract.md №31, FR-004): the adder adding
     * himself → `400 self_forbidden`; a plain member cannot manage the
     * roster (his payload is his OWN contact, so only the role can refuse)
     * → `403 forbidden_role`; a non-member adder hits the membership gate
     * → the uniform `404 group_not_found`.
     */
    @Test
    fun `addMembers refuses self-addition the member role and non-member adders`() {
        val owner = messagingUser("owner")
        val bob = messagingUser("bob")
        val stranger = messagingUser("erin")
        addContact(owner, bob.id)
        addContact(bob, stranger.id)
        val chatId = chatIdOf(createGroupOk(owner, GROUP_TITLE, memberUserIds = listOf(bob.id)))

        val selfAdd = addGroupMembers(owner, chatId, listOf(owner.id))
        assertThat(selfAdd.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertProblem(selfAdd, USER_IDS_FIELD, SELF_FORBIDDEN)

        val memberAdd = addGroupMembers(bob, chatId, listOf(stranger.id))
        assertThat(memberAdd.statusCode)
            .overridingErrorMessage(
                "a plain member must not manage the roster (FR-004), got <%s>: %s",
                memberAdd.statusCode,
                memberAdd.body,
            ).isEqualTo(HttpStatus.FORBIDDEN)
        assertProblem(memberAdd, GROUP_FIELD, FORBIDDEN_ROLE)

        assertGroupNotFound(addGroupMembers(stranger, chatId, listOf(bob.id)))
    }

    /** Contract №27 `POST /api/v1/groups` — raw response for status/error assertions. */
    private fun createGroup(
        owner: MessagingUser,
        title: String,
        description: String? = null,
        memberUserIds: List<UUID> = emptyList(),
    ): ResponseEntity<String> =
        postJsonAuthorized(
            GROUPS_PATH,
            buildMap {
                put(TITLE_FIELD, title)
                description?.let { put(DESCRIPTION_FIELD, it) }
                if (memberUserIds.isNotEmpty()) {
                    put(MEMBER_USER_IDS_FIELD, memberUserIds.map(UUID::toString))
                }
            },
            owner,
        )

    /** №27 happy path → the stored `GroupView` body. */
    private fun createGroupOk(
        owner: MessagingUser,
        title: String,
        description: String? = null,
        memberUserIds: List<UUID> = emptyList(),
    ): JsonNode {
        val response = createGroup(owner, title, description, memberUserIds)
        assertThat(response.statusCode)
            .overridingErrorMessage("№27 create must answer 201, got <%s>: %s", response.statusCode, response.body)
            .isEqualTo(HttpStatus.CREATED)
        return objectMapper.readTree(response.body)
    }

    /** Contract №28 `GET /api/v1/groups/{chatId}` — raw response. */
    private fun getGroup(
        user: MessagingUser,
        chatId: UUID,
    ): ResponseEntity<String> = exchangeWithAuth(HttpMethod.GET, "$GROUPS_PATH/$chatId", user)

    /** Contract №31 `POST /api/v1/groups/{chatId}/members` — raw response. */
    private fun addGroupMembers(
        user: MessagingUser,
        chatId: UUID,
        userIds: List<UUID>,
    ): ResponseEntity<String> =
        postJsonAuthorized(
            "$GROUPS_PATH/$chatId/members",
            mapOf(USER_IDS_FIELD to userIds.map(UUID::toString)),
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

    private fun exchangeWithAuth(
        method: HttpMethod,
        path: String,
        user: MessagingUser,
    ): ResponseEntity<String> =
        restTemplate.exchange(
            path,
            method,
            HttpEntity<Unit>(Unit, HttpHeaders().apply { setBearerAuth(user.accessToken) }),
            String::class.java,
        )

    private fun chatIdOf(view: JsonNode): UUID = UUID.fromString(view["chatId"].asText())

    private fun roleOf(
        members: List<JsonNode>,
        userId: UUID,
    ): String = members.single { it["user"]["id"].asText() == userId.toString() }["role"].asText()

    /** The caller's №12 item of the group — the unified list must carry it (FR-014). */
    private fun groupListItemOf(
        user: MessagingUser,
        chatId: UUID,
    ): JsonNode {
        val response = listChats(user)
        assertThat(response.statusCode)
            .overridingErrorMessage("№12 must answer 200, got <%s>: %s", response.statusCode, response.body)
            .isEqualTo(HttpStatus.OK)
        val item =
            objectMapper
                .readTree(response.body)["chats"]
                .toList()
                .singleOrNull { it["chatId"].asText() == chatId.toString() }
        assertThat(item)
            .overridingErrorMessage("the №12 list of <%s> must contain the group <%s>", user.username, chatId)
            .isNotNull
        return item!!
    }

    /** The uniform №28/№31 refusal: `404` problem `errors: {group: [group_not_found]}` (FR-008/FR-009). */
    private fun assertGroupNotFound(response: ResponseEntity<String>) {
        assertThat(response.statusCode)
            .overridingErrorMessage(
                "the group resource must be refused with the uniform 404, got <%s>: %s",
                response.statusCode,
                response.body,
            ).isEqualTo(HttpStatus.NOT_FOUND)
        assertProblem(response, GROUP_FIELD, GROUP_NOT_FOUND)
    }

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

    /** A contract-REQUIRED nullable field must be PRESENT as JSON null (not omitted). */
    private fun assertNullField(
        node: JsonNode,
        field: String,
    ) {
        assertThat(node.has(field) && node[field].isNull)
            .overridingErrorMessage(
                "field <%s> must be present as JSON null on the group element, got <%s>",
                field,
                node[field],
            ).isTrue
    }

    private fun fieldNames(node: JsonNode): List<String> = node.fieldNames().asSequence().toList()

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

    private companion object {
        const val GROUPS_PATH = "/api/v1/groups"
        const val CONTACTS_PATH = "/api/v1/contacts"

        const val PROBLEM_JSON_MEDIA_TYPE = "application/problem+json"

        const val GROUP_TITLE = "WebChat Project IT"
        const val GROUP_DESCRIPTION = "the US1 creation-slice fixture group"
        const val BLANK_TITLE = "   "

        /** api-contract.md №27 constants: title 1–64 after trim, description ≤256. */
        const val TITLE_TOO_LONG = 65
        const val DESCRIPTION_TOO_LONG = 257
        const val HISTORY_MESSAGES = 3
        const val QUOTE_CONTEXT_LENGTH = 8

        const val TITLE_FIELD = "title"
        const val DESCRIPTION_FIELD = "description"
        const val MEMBER_USER_IDS_FIELD = "memberUserIds"
        const val USER_IDS_FIELD = "userIds"
        const val USER_ID_FIELD = "userId"
        const val GROUP_FIELD = "group"

        const val INVALID_TITLE = "invalid_title"
        const val INVALID_DESCRIPTION = "invalid_description"
        const val NOT_IN_CONTACTS = "not_in_contacts"
        const val SELF_FORBIDDEN = "self_forbidden"
        const val FORBIDDEN_ROLE = "forbidden_role"
        const val GROUP_NOT_FOUND = "group_not_found"

        /** openapi.yaml 0.6.0 shapes — every group answer is pinned field-by-field. */
        val GROUP_VIEW_FIELDS = listOf("chatId", "title", "description", "myRole", "members")
        val GROUP_MEMBER_FIELDS = listOf("user", "role", "joinedAt")
        val PUBLIC_USER_FIELDS = listOf("id", "username", "email", "status", "createdAt")
        val GROUP_CHAT_LIST_ITEM_FIELDS =
            listOf(
                "chatId",
                "type",
                "title",
                "memberCount",
                "myRole",
                "peer",
                "lastMessage",
                "unreadCount",
                "blockedByMe",
            )
        val GROUP_CHAT_VIEW_FIELDS =
            listOf(
                "chatId",
                "type",
                "title",
                "description",
                "myRole",
                "othersReadUpToSeq",
                "memberCount",
                "peer",
                "blockedByMe",
                "peerReadUpToSeq",
                "myReadUpToSeq",
            )
    }
}
