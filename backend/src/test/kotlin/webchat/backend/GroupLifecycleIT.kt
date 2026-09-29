package webchat.backend

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import webchat.backend.sync.SyncTestSupport
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
 * T040 (US3) later grew the ROLES SLICE on top of the same staging —
 * one method per FR-003/FR-004 rule of api-contract.md §2:
 *
 *  * №34 `PUT /groups/{chatId}/members/{userId}/role` — ONLY the owner
 *    grants/revokes the admin role (`403 not_group_owner` for everyone
 *    else), never to himself (`400 self_forbidden`), only for an ACTIVE
 *    target (`409 target_not_member`) and only the `admin|member` values
 *    (`400 invalid_role` — the owner role is №35-only); every change is
 *    broadcast to all actives as `group.role.changed`;
 *  * №35 `POST /groups/{chatId}/owner` — ONE transaction demote THEN
 *    promote: the answer carries the new owner and the former owner as
 *    admin, the partial unique index keeps EXACTLY one owner row, the
 *    transfer to self is `400 self_forbidden` (edge) and every active
 *    receives the `group.role.changed` pair of frames;
 *  * №32 `DELETE /groups/{chatId}/members/{userId}` — the kick: an admin
 *    removes ONLY members (`403 role_hierarchy_violation` on an
 *    admin/owner target), a plain member removes no one (`403
 *    forbidden_role`), the self-kick is refused `400 self_forbidden`
 *    BEFORE the role hierarchy (leaving is №33), the removed member
 *    gets the final `group.you_removed {reason:'kicked'}` and the
 *    remaining actives `group.member.removed`; his messages stay in the
 *    history with attribution, his №28 collapses to the uniform 404 and
 *    a repeated №32 on the LIVE group answers `409 target_not_member`
 *    (api-contract.md §2 «Идемпотентность»).
 *
 * T049 (US4) grew the METADATA SLICE on top of the same staging —
 * №29 `PATCH /groups/{chatId}` of api-contract.md §2 (FR-007):
 *
 *  * the owner/admin gate — a plain member managing metadata gets
 *    `403 forbidden_role` (FR-004), a non-member/nonexistent target hits
 *    the uniform privacy `404 group_not_found` (§1);
 *  * the №27 validation reused verbatim: a blank/65-char title → `400
 *    invalid_title`, a 257-char description → `400 invalid_description`,
 *    a body with NEITHER field → `400 empty_patch` (errors.body), and a
 *    MIXED patch is refused atomically — nothing lands, nothing journals;
 *  * a confirmed patch answers the `GroupView`, №28/№12 serve the new
 *    metadata on the next load, EVERY active receives `group.updated`
 *    (realtime-group-events.md §3.1) and the journal grows
 *    `title_changed`/`description_changed` of the actor (FR-017);
 *  * the EDGE of two admins patching SIMULTANEOUSLY: both operations
 *    confirm whole (the FOR UPDATE serialization) and the stored
 *    (title, description) pair is EXACTLY one of the two patches — the
 *    last confirmed — never a mix of the two.
 *
 * NOTE (TDD, constitution VI): the creation slice was written BEFORE the
 * US1 implementation tasks (T020–T024); T040 grew the roles slice and T049
 * the metadata slice the same way — until the US4 implementation tasks
 * (T051/T052) land, №29 answers 405, so every metadata method fails (RED)
 * by design. Limits (200/64/256/batch) and the №31 idempotency matrix are
 * owned by T018a (GroupLimitsIT); the leave-delete slice of this lifecycle
 * (T059) EXTENDS this class later — keep every fixture self-contained per
 * method.
 */
@Suppress("TooManyFunctions", "LargeClass") // T018: one method per US1 rule of tasks.md
class GroupLifecycleIT(
    @Autowired private val restTemplate: TestRestTemplate,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) : SyncTestSupport() {
    /** A role frame must arrive well inside this CI-tolerant budget (SC-004 functional leg). */
    private val deliveryBudget: Duration = Duration.ofSeconds(DELIVERY_BUDGET_SECONDS)

    /** The random HTTP port — the JDK-HttpClient №29 leg needs the absolute URL. */
    @LocalServerPort
    private var serverPort: Int = 0

    /**
     * One shared client for the №29 calls (connection reuse over the test
     * JVM lifetime) — the JDK transport the SSE reader already uses.
     */
    private val patchHttpClient =
        HttpClient
            .newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(PATCH_CONNECT_TIMEOUT_SECONDS))
            .build()

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

    /**
     * №34 happy path (FR-003, api-contract.md №34): the owner grants bob
     * the admin role — `200` with the bare `GroupMember` (role `admin`) —
     * and №28 reflects the change immediately; the revoke (role `member`)
     * mirrors it back. EVERY change is broadcast to all three actives as
     * `group.role.changed` carrying the contract payload verbatim
     * (realtime-group-events.md §3.4).
     */
    @Test
    fun `setMemberRole grants and revokes the admin role for the owner with the role frames`() {
        val owner = messagingUser("owner")
        val (bob, carol) = messagingUser("bob") to messagingUser("carol")
        addContact(owner, bob.id)
        addContact(owner, carol.id)
        val chatId = chatIdOf(createGroupOk(owner, GROUP_TITLE, memberUserIds = listOf(bob.id, carol.id)))

        openUserEvents(owner).use { ownerStream ->
            openUserEvents(bob).use { bobStream ->
                openUserEvents(carol).use { carolStream ->
                    val grantedMember = grantAdminAndAssertView(owner, bob, chatId)
                    awaitRoleChangedOnAll(
                        streams = listOf(ownerStream, bobStream, carolStream),
                        chatId = chatId,
                        userId = bob.id,
                        role = ADMIN_ROLE,
                        actorId = owner.id,
                    )
                    revokeAdminAndAssertView(owner, bob, chatId, grantedMember)
                    assertRoleChangedFrame(
                        ownerStream.awaitEvent(GROUP_ROLE_CHANGED_EVENT, deliveryBudget),
                        chatId = chatId,
                        userId = bob.id,
                        role = MEMBER_ROLE,
                        actorId = owner.id,
                    )
                }
            }
        }
    }

    /** The №34 grant leg: `200 GroupMember{role=admin}` and the immediate №28 reflection. */
    private fun grantAdminAndAssertView(
        owner: MessagingUser,
        bob: MessagingUser,
        chatId: UUID,
    ): JsonNode {
        val granted = setMemberRole(owner, chatId, bob.id, ADMIN_ROLE)
        assertThat(granted.statusCode)
            .overridingErrorMessage(
                "№34 admin grant from the owner must answer 200, got <%s>: %s",
                granted.statusCode,
                granted.body,
            ).isEqualTo(HttpStatus.OK)
        val grantedMember = objectMapper.readTree(granted.body)
        assertThat(fieldNames(grantedMember))
            .overridingErrorMessage(
                "the №34 answer must be exactly the GroupMember schema, got <%s>",
                fieldNames(grantedMember),
            ).containsExactlyInAnyOrderElementsOf(GROUP_MEMBER_FIELDS)
        assertThat(grantedMember["user"]["id"].asText()).isEqualTo(bob.id.toString())
        assertThat(grantedMember["role"].asText()).isEqualTo(ADMIN_ROLE)
        val grantedView = objectMapper.readTree(getGroup(bob, chatId).body)
        assertThat(roleOf(grantedView["members"].toList(), bob.id))
            .overridingErrorMessage("№28 must reflect the granted admin role immediately")
            .isEqualTo(ADMIN_ROLE)
        return grantedMember
    }

    /** The №34 revoke leg: `200 GroupMember{role=member}` and the №28 reflection back. */
    private fun revokeAdminAndAssertView(
        owner: MessagingUser,
        bob: MessagingUser,
        chatId: UUID,
        grantedMember: JsonNode,
    ) {
        val revoked = setMemberRole(owner, chatId, bob.id, MEMBER_ROLE)
        assertThat(revoked.statusCode)
            .overridingErrorMessage(
                "№34 revoke to member must answer 200, got <%s>: %s",
                revoked.statusCode,
                revoked.body,
            ).isEqualTo(HttpStatus.OK)
        val revokedMember = objectMapper.readTree(revoked.body)
        assertThat(revokedMember["role"].asText()).isEqualTo(MEMBER_ROLE)
        assertThat(revokedMember["user"]["id"].asText())
            .isEqualTo(grantedMember["user"]["id"].asText())
        val revokedView = objectMapper.readTree(getGroup(owner, chatId).body)
        assertThat(roleOf(revokedView["members"].toList(), bob.id)).isEqualTo(MEMBER_ROLE)
    }

    /**
     * №34 refusals (FR-003/FR-008, api-contract.md №34): the owner-only
     * rule — a plain member caller gets `403 not_group_owner`; the owner
     * targeting himself gets `400 self_forbidden` (his role changes only
     * through №35); a target outside the ACTIVE roster gets `409
     * target_not_member`; the values beyond `admin|member` (including the
     * №35-reserved `owner`) get `400 invalid_role`; a caller without an
     * active membership hits the uniform privacy gate `404
     * group_not_found` (existence is never disclosed).
     */
    @Test
    fun `setMemberRole refuses non-owners self targets outside the roster and alien roles`() {
        val owner = messagingUser("owner")
        val (bob, carol) = messagingUser("bob") to messagingUser("carol")
        val stranger = messagingUser("erin")
        addContact(owner, bob.id)
        addContact(owner, carol.id)
        val chatId = chatIdOf(createGroupOk(owner, GROUP_TITLE, memberUserIds = listOf(bob.id, carol.id)))

        val byMember = setMemberRole(bob, chatId, carol.id, ADMIN_ROLE)
        assertThat(byMember.statusCode)
            .overridingErrorMessage(
                "a plain member must not change roles (FR-003), got <%s>: %s",
                byMember.statusCode,
                byMember.body,
            ).isEqualTo(HttpStatus.FORBIDDEN)
        assertProblem(byMember, GROUP_FIELD, NOT_GROUP_OWNER)

        val selfRole = setMemberRole(owner, chatId, owner.id, ADMIN_ROLE)
        assertThat(selfRole.statusCode)
            .overridingErrorMessage(
                "the owner must not change his own role through №34 (№35 only), got <%s>: %s",
                selfRole.statusCode,
                selfRole.body,
            ).isEqualTo(HttpStatus.BAD_REQUEST)
        assertProblem(selfRole, USER_ID_FIELD, SELF_FORBIDDEN)

        val outsideRoster = setMemberRole(owner, chatId, stranger.id, ADMIN_ROLE)
        assertThat(outsideRoster.statusCode)
            .overridingErrorMessage(
                "a target outside the active roster must be refused 409, got <%s>: %s",
                outsideRoster.statusCode,
                outsideRoster.body,
            ).isEqualTo(HttpStatus.CONFLICT)
        assertProblem(outsideRoster, USER_ID_FIELD, TARGET_NOT_MEMBER)

        for (alienRole in listOf(OWNER_ROLE, ALIEN_ROLE)) {
            val invalid = setMemberRole(owner, chatId, bob.id, alienRole)
            assertThat(invalid.statusCode)
                .overridingErrorMessage(
                    "role <%s> is not settable through №34 (only admin|member), got <%s>: %s",
                    alienRole,
                    invalid.statusCode,
                    invalid.body,
                ).isEqualTo(HttpStatus.BAD_REQUEST)
            assertProblem(invalid, ROLE_FIELD, INVALID_ROLE)
        }

        assertGroupNotFound(setMemberRole(stranger, chatId, bob.id, ADMIN_ROLE))
    }

    /**
     * №35 happy path (FR-003, api-contract.md №35): ONE transaction demote
     * THEN promote — the answer carries the caller's `GroupView` with his
     * NEW `admin` role, the target as the single `owner`, and №28 of both
     * sides converges; the partial unique index keeps EXACTLY one active
     * owner row (the invariant of data-model.md §Миграция); every active
     * participant receives the `group.role.changed` PAIR of frames — the
     * new owner and the former owner demoted to admin
     * (realtime-group-events.md §3.4).
     */
    @Test
    fun `transferOwnership demotes then promotes keeping exactly one owner`() {
        val owner = messagingUser("owner")
        val (bob, carol) = messagingUser("bob") to messagingUser("carol")
        addContact(owner, bob.id)
        addContact(owner, carol.id)
        val chatId = chatIdOf(createGroupOk(owner, GROUP_TITLE, memberUserIds = listOf(bob.id, carol.id)))

        openUserEvents(owner).use { ownerStream ->
            openUserEvents(bob).use { bobStream ->
                openUserEvents(carol).use { carolStream ->
                    assertTransferredView(owner, bob, carol, chatId)
                    awaitTransferPairOnAll(
                        streams = listOf(ownerStream, bobStream, carolStream),
                        chatId = chatId,
                        newOwner = bob,
                        formerOwner = owner,
                    )
                }
            }
        }
    }

    /** The №35 answer + №28 convergence + the single-owner invariant (ux index). */
    private fun assertTransferredView(
        owner: MessagingUser,
        bob: MessagingUser,
        carol: MessagingUser,
        chatId: UUID,
    ) {
        val response = transferOwnership(owner, chatId, bob.id)
        assertThat(response.statusCode)
            .overridingErrorMessage(
                "№35 transfer from the owner must answer 200, got <%s>: %s",
                response.statusCode,
                response.body,
            ).isEqualTo(HttpStatus.OK)
        val view = objectMapper.readTree(response.body)
        assertThat(fieldNames(view))
            .overridingErrorMessage(
                "the №35 answer must be exactly the GroupView schema, got <%s>",
                fieldNames(view),
            ).containsExactlyInAnyOrderElementsOf(GROUP_VIEW_FIELDS)
        assertThat(view["myRole"].asText())
            .overridingErrorMessage("the former owner reads his own view as the new admin")
            .isEqualTo(ADMIN_ROLE)
        val members = view["members"].toList()
        assertThat(roleOf(members, bob.id)).isEqualTo(OWNER_ROLE)
        assertThat(roleOf(members, owner.id)).isEqualTo(ADMIN_ROLE)
        assertThat(roleOf(members, carol.id)).isEqualTo(MEMBER_ROLE)

        assertThat(objectMapper.readTree(getGroup(bob, chatId).body)["myRole"].asText())
            .overridingErrorMessage("the new owner must read №28 with myRole=owner")
            .isEqualTo(OWNER_ROLE)
        assertThat(objectMapper.readTree(getGroup(owner, chatId).body)["myRole"].asText())
            .isEqualTo(ADMIN_ROLE)
        assertThat(activeOwnerCount(chatId))
            .overridingErrorMessage(
                "the transfer must keep EXACTLY one active owner row (ux_chat_participants_owner)",
            ).isEqualTo(1)
    }

    /**
     * §3.4 wire leg: every active stream receives the `group.role.changed`
     * PAIR — the new owner promoted and the former demoted to admin — as
     * an unordered pair of frames carrying the contract fields verbatim.
     */
    private fun awaitTransferPairOnAll(
        streams: List<UserEventsStream>,
        chatId: UUID,
        newOwner: MessagingUser,
        formerOwner: MessagingUser,
    ) {
        streams.forEach { stream ->
            val pair =
                listOf(
                    stream.awaitEvent(GROUP_ROLE_CHANGED_EVENT, deliveryBudget),
                    stream.awaitEvent(GROUP_ROLE_CHANGED_EVENT, deliveryBudget),
                )
            assertThat(pair.map { it["userId"].asText() to it["role"].asText() })
                .overridingErrorMessage(
                    "every active must receive the role.changed PAIR — new owner + former demoted, got: %s",
                    pair.map { it["userId"].asText() to it["role"].asText() },
                ).containsExactlyInAnyOrder(
                    newOwner.id.toString() to OWNER_ROLE,
                    formerOwner.id.toString() to ADMIN_ROLE,
                )
            pair.forEach { frame ->
                assertThat(fieldNames(frame))
                    .overridingErrorMessage(
                        "group.role.changed must carry exactly the contract fields, got <%s>",
                        fieldNames(frame),
                    ).containsExactlyInAnyOrderElementsOf(GROUP_ROLE_CHANGED_EVENT_FIELDS)
                assertThat(frame["groupId"].asText()).isEqualTo(chatId.toString())
                assertThat(frame["actorId"].asText()).isEqualTo(formerOwner.id.toString())
            }
        }
    }

    /**
     * №35 refusals (FR-003/FR-008, api-contract.md №35): the transfer to
     * self is the contract edge `400 self_forbidden`; an admin (a proven
     * member of sufficient-for-nothing-here role) gets `403
     * not_group_owner`; a target outside the ACTIVE roster gets `409
     * target_not_member`; a non-member caller gets the uniform `404
     * group_not_found`.
     */
    @Test
    fun `transferOwnership refuses self non-owners and targets outside the roster`() {
        val owner = messagingUser("owner")
        val (bob, carol) = messagingUser("bob") to messagingUser("carol")
        val stranger = messagingUser("erin")
        addContact(owner, bob.id)
        addContact(owner, carol.id)
        val chatId = chatIdOf(createGroupOk(owner, GROUP_TITLE, memberUserIds = listOf(bob.id, carol.id)))
        grantAdminOk(owner, chatId, bob.id)

        val toSelf = transferOwnership(owner, chatId, owner.id)
        assertThat(toSelf.statusCode)
            .overridingErrorMessage(
                "the transfer to self must be the contract edge 400 self_forbidden, got <%s>: %s",
                toSelf.statusCode,
                toSelf.body,
            ).isEqualTo(HttpStatus.BAD_REQUEST)
        assertProblem(toSelf, USER_ID_FIELD, SELF_FORBIDDEN)

        val byAdmin = transferOwnership(bob, chatId, carol.id)
        assertThat(byAdmin.statusCode)
            .overridingErrorMessage(
                "only the owner may transfer ownership (FR-003), got <%s>: %s",
                byAdmin.statusCode,
                byAdmin.body,
            ).isEqualTo(HttpStatus.FORBIDDEN)
        assertProblem(byAdmin, GROUP_FIELD, NOT_GROUP_OWNER)

        val outsideRoster = transferOwnership(owner, chatId, stranger.id)
        assertThat(outsideRoster.statusCode)
            .overridingErrorMessage(
                "a target outside the active roster must be refused 409, got <%s>: %s",
                outsideRoster.statusCode,
                outsideRoster.body,
            ).isEqualTo(HttpStatus.CONFLICT)
        assertProblem(outsideRoster, USER_ID_FIELD, TARGET_NOT_MEMBER)

        assertGroupNotFound(transferOwnership(stranger, chatId, bob.id))
    }

    /**
     * №32 happy path (FR-004/FR-010, api-contract.md №32): an admin kicks
     * a plain member — `204`; the removed member's №28 collapses to the
     * uniform `404 group_not_found`; he receives the FINAL
     * `group.you_removed {reason:'kicked'}` while the remaining actives
     * receive `group.member.removed` with the actor; the roster of the
     * survivors no longer carries him; and his messages STAY in the №15
     * history with attribution.
     */
    @Test
    fun `kick removes a member closes his view and keeps his messages`() {
        val owner = messagingUser("owner")
        val (bob, carol, dave) = listOf("bob", "carol", "dave").map { messagingUser(it) }
        val trio = listOf(bob, carol, dave)
        trio.forEach { addContact(owner, it.id) }
        val chatId = chatIdOf(createGroupOk(owner, GROUP_TITLE, memberUserIds = trio.map { it.id }))
        grantAdminOk(owner, chatId, bob.id)
        val seeded = seedChatBacklog(chatId, KICK_SEEDED_MESSAGES, senderFor = { carol }, KICK_TEXT_PREFIX)
        assertThat(seeded)
            .overridingErrorMessage("the fixture must seed exactly one message of the kick target")
            .hasSize(1)

        openUserEvents(carol).use { carolStream ->
            openUserEvents(dave).use { daveStream ->
                val response = kickMember(bob, chatId, carol.id)
                assertThat(response.statusCode)
                    .overridingErrorMessage(
                        "№32 kick of a member by an admin must answer 204, got <%s>: %s",
                        response.statusCode,
                        response.body,
                    ).isEqualTo(HttpStatus.NO_CONTENT)

                assertMemberRemovedFrame(
                    daveStream.awaitEvent(GROUP_MEMBER_REMOVED_EVENT, deliveryBudget),
                    chatId = chatId,
                    userId = carol.id,
                    actorId = bob.id,
                )
                val finalFrame = carolStream.awaitEvent(GROUP_YOU_REMOVED_EVENT, deliveryBudget)
                assertThat(fieldNames(finalFrame))
                    .overridingErrorMessage(
                        "group.you_removed must carry exactly the contract fields, got <%s>",
                        fieldNames(finalFrame),
                    ).containsExactlyInAnyOrderElementsOf(GROUP_YOU_REMOVED_EVENT_FIELDS)
                assertThat(finalFrame["groupId"].asText()).isEqualTo(chatId.toString())
                assertThat(finalFrame["reason"].asText())
                    .overridingErrorMessage("a №32 removal is the 'kicked' reason (realtime-group-events.md §3.6)")
                    .isEqualTo(KICKED_REASON)

                assertGroupNotFound(getGroup(carol, chatId))
                val roster = objectMapper.readTree(getGroup(dave, chatId).body)["members"].toList()
                assertThat(roster.map { it["user"]["id"].asText() })
                    .overridingErrorMessage("the survivor roster must drop the kicked member")
                    .containsExactlyInAnyOrder(
                        owner.id.toString(),
                        bob.id.toString(),
                        dave.id.toString(),
                    )

                val history = listMessages(dave, chatId)
                assertThat(history.statusCode)
                    .overridingErrorMessage("№15 of a survivor must stay readable, got <%s>", history.statusCode)
                    .isEqualTo(HttpStatus.OK)
                assertThat(objectMapper.readTree(history.body)["messages"].map { it["id"].asText() })
                    .overridingErrorMessage(
                        "the kicked member's messages must REMAIN in the history (api-contract.md №32)",
                    ).containsExactly(seeded.single().id.toString())
            }
        }
    }

    /**
     * №32 hierarchy (FR-004, api-contract.md №32): an admin removes ONLY
     * members — an admin/owner target is `403 role_hierarchy_violation`;
     * a plain member removes no one at all (`403 forbidden_role`); the
     * owner removes anyone but himself — here an admin — with `204`, and
     * the removed admin's №28 collapses to the uniform 404.
     */
    @Test
    fun `kick enforces the role hierarchy`() {
        val owner = messagingUser("owner")
        val (bob, carol, dave) = listOf("bob", "carol", "dave").map { messagingUser(it) }
        val trio = listOf(bob, carol, dave)
        trio.forEach { addContact(owner, it.id) }
        val chatId = chatIdOf(createGroupOk(owner, GROUP_TITLE, memberUserIds = trio.map { it.id }))
        grantAdminOk(owner, chatId, bob.id)
        grantAdminOk(owner, chatId, carol.id)

        val adminOnAdmin = kickMember(bob, chatId, carol.id)
        assertThat(adminOnAdmin.statusCode)
            .overridingErrorMessage(
                "an admin kicking an admin must hit the hierarchy rule, got <%s>: %s",
                adminOnAdmin.statusCode,
                adminOnAdmin.body,
            ).isEqualTo(HttpStatus.FORBIDDEN)
        assertProblem(adminOnAdmin, USER_ID_FIELD, ROLE_HIERARCHY_VIOLATION)

        val adminOnOwner = kickMember(bob, chatId, owner.id)
        assertThat(adminOnOwner.statusCode)
            .overridingErrorMessage(
                "an admin kicking the owner must hit the hierarchy rule, got <%s>: %s",
                adminOnOwner.statusCode,
                adminOnOwner.body,
            ).isEqualTo(HttpStatus.FORBIDDEN)
        assertProblem(adminOnOwner, USER_ID_FIELD, ROLE_HIERARCHY_VIOLATION)

        val byMember = kickMember(dave, chatId, bob.id)
        assertThat(byMember.statusCode)
            .overridingErrorMessage(
                "a plain member must remove no one (FR-004), got <%s>: %s",
                byMember.statusCode,
                byMember.body,
            ).isEqualTo(HttpStatus.FORBIDDEN)
        assertProblem(byMember, GROUP_FIELD, FORBIDDEN_ROLE)

        val ownerOnAdmin = kickMember(owner, chatId, carol.id)
        assertThat(ownerOnAdmin.statusCode)
            .overridingErrorMessage(
                "the owner removes any member but himself — here an admin, got <%s>: %s",
                ownerOnAdmin.statusCode,
                ownerOnAdmin.body,
            ).isEqualTo(HttpStatus.NO_CONTENT)
        assertGroupNotFound(getGroup(carol, chatId))
    }

    /**
     * №32 self/idempotency/privacy (api-contract.md №2): the self-kick is
     * refused `400 self_forbidden` BEFORE the role hierarchy — even a
     * plain member kicking HIMSELF sees self_forbidden, never
     * forbidden_role (leaving is №33 only); a repeated №32 on the LIVE
     * group (the target already removed) answers `409 target_not_member`;
     * a non-member caller gets the uniform `404 group_not_found`.
     */
    @Test
    fun `kick refuses self before the hierarchy and a repeat as target_not_member`() {
        val owner = messagingUser("owner")
        val (bob, carol) = messagingUser("bob") to messagingUser("carol")
        val stranger = messagingUser("erin")
        addContact(owner, bob.id)
        addContact(owner, carol.id)
        val chatId = chatIdOf(createGroupOk(owner, GROUP_TITLE, memberUserIds = listOf(bob.id, carol.id)))

        val selfKick = kickMember(bob, chatId, bob.id)
        assertThat(selfKick.statusCode)
            .overridingErrorMessage(
                "the self-kick must be 400 self_forbidden BEFORE the hierarchy (never forbidden_role), got <%s>: %s",
                selfKick.statusCode,
                selfKick.body,
            ).isEqualTo(HttpStatus.BAD_REQUEST)
        assertProblem(selfKick, USER_ID_FIELD, SELF_FORBIDDEN)

        val first = kickMember(owner, chatId, bob.id)
        assertThat(first.statusCode)
            .overridingErrorMessage(
                "the owner's kick of a member must answer 204, got <%s>: %s",
                first.statusCode,
                first.body,
            ).isEqualTo(HttpStatus.NO_CONTENT)

        val repeat = kickMember(owner, chatId, bob.id)
        assertThat(repeat.statusCode)
            .overridingErrorMessage(
                "a repeated №32 on the LIVE group (target already removed) must answer 409 " +
                    "(api-contract.md §2), got <%s>: %s",
                repeat.statusCode,
                repeat.body,
            ).isEqualTo(HttpStatus.CONFLICT)
        assertProblem(repeat, USER_ID_FIELD, TARGET_NOT_MEMBER)

        assertGroupNotFound(kickMember(stranger, chatId, carol.id))
    }

    /**
     * №29 happy path (FR-007, api-contract.md №29): an ADMIN patches title
     * AND description — `200` with the exact `GroupView` of the new
     * metadata; №28/№12 of the OTHER members serve it on the next load
     * (changes MUST survive reloads); EVERY active (the actor included)
     * receives `group.updated` carrying the §3.1 payload verbatim; the
     * journal grows one `title_changed` + one `description_changed` of the
     * actor (FR-017).
     */
    @Test
    fun `updateGroup applies the whole patch broadcasts the frame and journals both actions`() {
        val owner = messagingUser("owner")
        val (bob, carol) = messagingUser("bob") to messagingUser("carol")
        addContact(owner, bob.id)
        addContact(owner, carol.id)
        val chatId = chatIdOf(createGroupOk(owner, GROUP_TITLE, GROUP_DESCRIPTION, listOf(bob.id, carol.id)))
        grantAdminOk(owner, chatId, bob.id)

        openUserEvents(owner).use { ownerStream ->
            openUserEvents(bob).use { bobStream ->
                openUserEvents(carol).use { carolStream ->
                    val response =
                        patchGroup(
                            bob,
                            chatId,
                            mapOf(TITLE_FIELD to RENAMED_TITLE, DESCRIPTION_FIELD to RENAMED_DESCRIPTION),
                        )
                    assertThat(response.statusCode)
                        .overridingErrorMessage(
                            "№29 by an admin must answer 200, got <%s>: %s",
                            response.statusCode,
                            response.body,
                        ).isEqualTo(HttpStatus.OK)
                    val view = objectMapper.readTree(response.body)
                    assertThat(fieldNames(view))
                        .overridingErrorMessage(
                            "the №29 answer must be exactly the GroupView schema, got <%s>",
                            fieldNames(view),
                        ).containsExactlyInAnyOrderElementsOf(GROUP_VIEW_FIELDS)
                    assertThat(view["title"].asText()).isEqualTo(RENAMED_TITLE)
                    assertThat(view["description"].asText()).isEqualTo(RENAMED_DESCRIPTION)
                    assertThat(view["myRole"].asText())
                        .overridingErrorMessage("the patching admin reads his own view with myRole=admin")
                        .isEqualTo(ADMIN_ROLE)

                    assertThat(objectMapper.readTree(getGroup(carol, chatId).body)["title"].asText())
                        .overridingErrorMessage("№28 must serve the new title on the next load (FR-007)")
                        .isEqualTo(RENAMED_TITLE)
                    assertThat(groupListItemOf(owner, chatId)["title"].asText())
                        .overridingErrorMessage("the №12 group element must carry the new title (FR-007/FR-014)")
                        .isEqualTo(RENAMED_TITLE)

                    awaitUpdatedOnAll(
                        streams = listOf(ownerStream, bobStream, carolStream),
                        chatId = chatId,
                        title = RENAMED_TITLE,
                        description = RENAMED_DESCRIPTION,
                        actorId = bob.id,
                    )

                    assertThat(adminLogActions(chatId, TITLE_CHANGED_ACTION, DESCRIPTION_CHANGED_ACTION))
                        .overridingErrorMessage(
                            "№29 must journal one title_changed + one description_changed of the actor (FR-017)",
                        ).containsExactlyInAnyOrder(
                            TITLE_CHANGED_ACTION to bob.id,
                            DESCRIPTION_CHANGED_ACTION to bob.id,
                        )
                }
            }
        }
    }

    /**
     * №29 validation (FR-001/FR-007, api-contract.md №29): a body with
     * neither field is `400 empty_patch` (errors.body); the №27 rules
     * reused verbatim — a blank/empty/65-char title is `400 invalid_title`,
     * a 257-char description `400 invalid_description`; and a MIXED patch
     * (valid title + invalid description) is refused ATOMICALLY: nothing
     * lands on №28 and nothing journals — the patch applies whole or not
     * at all.
     */
    @Test
    fun `updateGroup refuses an empty patch and invalid metadata atomically`() {
        val owner = messagingUser("owner")
        val bob = messagingUser("bob")
        addContact(owner, bob.id)
        val chatId = chatIdOf(createGroupOk(owner, GROUP_TITLE, GROUP_DESCRIPTION, listOf(bob.id)))

        val emptyPatch = patchGroup(owner, chatId, emptyMap())
        assertThat(emptyPatch.statusCode)
            .overridingErrorMessage(
                "a №29 body with neither field must answer 400, got <%s>: %s",
                emptyPatch.statusCode,
                emptyPatch.body,
            ).isEqualTo(HttpStatus.BAD_REQUEST)
        assertProblem(emptyPatch, BODY_FIELD, EMPTY_PATCH)

        for (invalidTitle in listOf("", BLANK_TITLE, "x".repeat(TITLE_TOO_LONG))) {
            val response = patchGroup(owner, chatId, mapOf(TITLE_FIELD to invalidTitle))
            assertThat(response.statusCode)
                .overridingErrorMessage(
                    "patch title <%s…> of length ${invalidTitle.length} must be refused 400, got <%s>: %s",
                    invalidTitle.take(QUOTE_CONTEXT_LENGTH),
                    response.statusCode,
                    response.body,
                ).isEqualTo(HttpStatus.BAD_REQUEST)
            assertProblem(response, TITLE_FIELD, INVALID_TITLE)
        }

        val oversized =
            patchGroup(owner, chatId, mapOf(DESCRIPTION_FIELD to "d".repeat(DESCRIPTION_TOO_LONG)))
        assertThat(oversized.statusCode)
            .overridingErrorMessage(
                "a ${DESCRIPTION_TOO_LONG}-char patch description must be refused 400, got <%s>: %s",
                oversized.statusCode,
                oversized.body,
            ).isEqualTo(HttpStatus.BAD_REQUEST)
        assertProblem(oversized, DESCRIPTION_FIELD, INVALID_DESCRIPTION)

        val mixed =
            patchGroup(
                owner,
                chatId,
                mapOf(TITLE_FIELD to ATOMIC_REJECTED_TITLE, DESCRIPTION_FIELD to "d".repeat(DESCRIPTION_TOO_LONG)),
            )
        assertThat(mixed.statusCode)
            .overridingErrorMessage(
                "a MIXED patch with any invalid field must be refused 400, got <%s>: %s",
                mixed.statusCode,
                mixed.body,
            ).isEqualTo(HttpStatus.BAD_REQUEST)
        assertProblem(mixed, DESCRIPTION_FIELD, INVALID_DESCRIPTION)
        val unchanged = objectMapper.readTree(getGroup(owner, chatId).body)
        assertThat(unchanged["title"].asText())
            .overridingErrorMessage("a refused MIXED patch must land NOTHING — the whole №29 is atomic")
            .isEqualTo(GROUP_TITLE)
        assertThat(unchanged["description"].asText()).isEqualTo(GROUP_DESCRIPTION)
        assertThat(adminLogActions(chatId, TITLE_CHANGED_ACTION, DESCRIPTION_CHANGED_ACTION))
            .overridingErrorMessage("a refused patch journals nothing (FR-017)")
            .isEmpty()
    }

    /**
     * №29 role gate + privacy floor (FR-004/FR-008, api-contract.md №29/§1):
     * a plain member does not manage metadata — `403 forbidden_role`
     * (errors.group) with nothing journaled; a non-member and a
     * nonexistent chatId get the SAME uniform `404 group_not_found` —
     * existence is never disclosed.
     */
    @Test
    fun `updateGroup refuses the member role and non-members`() {
        val owner = messagingUser("owner")
        val (bob, carol) = messagingUser("bob") to messagingUser("carol")
        val stranger = messagingUser("erin")
        addContact(owner, bob.id)
        addContact(owner, carol.id)
        val chatId = chatIdOf(createGroupOk(owner, GROUP_TITLE, memberUserIds = listOf(bob.id, carol.id)))

        val byMember = patchGroup(carol, chatId, mapOf(TITLE_FIELD to MEMBER_REJECTED_TITLE))
        assertThat(byMember.statusCode)
            .overridingErrorMessage(
                "a plain member must not manage metadata (FR-004/FR-007), got <%s>: %s",
                byMember.statusCode,
                byMember.body,
            ).isEqualTo(HttpStatus.FORBIDDEN)
        assertProblem(byMember, GROUP_FIELD, FORBIDDEN_ROLE)
        assertThat(objectMapper.readTree(getGroup(owner, chatId).body)["title"].asText())
            .overridingErrorMessage("the refused member patch must change nothing")
            .isEqualTo(GROUP_TITLE)
        assertThat(adminLogActions(chatId, TITLE_CHANGED_ACTION, DESCRIPTION_CHANGED_ACTION))
            .overridingErrorMessage("the refused member patch journals nothing (FR-017)")
            .isEmpty()

        assertGroupNotFound(patchGroup(stranger, chatId, mapOf(TITLE_FIELD to MEMBER_REJECTED_TITLE)))
        assertGroupNotFound(patchGroup(owner, UUID.randomUUID(), mapOf(TITLE_FIELD to MEMBER_REJECTED_TITLE)))
    }

    /**
     * №29 simultaneous-patch edge (FR-007, api-contract.md №29, spec.md
     * Edge «одновременное переименование»): two admins patch title AND
     * description at the same moment — BOTH operations confirm with 200
     * (the FOR UPDATE serialization orders them) and the stored
     * (title, description) pair is EXACTLY one whole patch — the last
     * confirmed — never a mix of the two; the journal carries BOTH
     * confirmations (an action pair per admin) and every active receives
     * BOTH `group.updated` frames, each repeating one whole patch payload.
     */
    @Test
    fun `two admins patching concurrently land exactly one whole confirmed patch`() {
        val owner = messagingUser("owner")
        val (bob, carol, dave) = listOf("bob", "carol", "dave").map { messagingUser(it) }
        val trio = listOf(bob, carol, dave)
        trio.forEach { addContact(owner, it.id) }
        val chatId = chatIdOf(createGroupOk(owner, GROUP_TITLE, memberUserIds = trio.map { it.id }))
        grantAdminOk(owner, chatId, bob.id)
        grantAdminOk(owner, chatId, carol.id)
        val bobPatch = mapOf(TITLE_FIELD to BOB_RENAME_TITLE, DESCRIPTION_FIELD to BOB_RENAME_DESCRIPTION)
        val carolPatch = mapOf(TITLE_FIELD to CAROL_RENAME_TITLE, DESCRIPTION_FIELD to CAROL_RENAME_DESCRIPTION)
        val wholePatches =
            listOf(
                BOB_RENAME_TITLE to BOB_RENAME_DESCRIPTION,
                CAROL_RENAME_TITLE to CAROL_RENAME_DESCRIPTION,
            )

        openUserEvents(bob).use { bobStream ->
            openUserEvents(carol).use { carolStream ->
                openUserEvents(dave).use { daveStream ->
                    fireConcurrentPatches(bob, bobPatch, carol, carolPatch, chatId)
                    assertStoredWholePatch(dave, chatId, wholePatches)

                    assertThat(adminLogActions(chatId, TITLE_CHANGED_ACTION, DESCRIPTION_CHANGED_ACTION))
                        .overridingErrorMessage(
                            "every confirmed №29 journals its action pair — both admins land (FR-017)",
                        ).containsExactlyInAnyOrder(
                            TITLE_CHANGED_ACTION to bob.id,
                            DESCRIPTION_CHANGED_ACTION to bob.id,
                            TITLE_CHANGED_ACTION to carol.id,
                            DESCRIPTION_CHANGED_ACTION to carol.id,
                        )

                    awaitWholePatchFrames(listOf(bobStream, carolStream, daveStream), chatId, wholePatches)
                }
            }
        }
    }

    /** Fires the two №29 patches of the edge simultaneously and asserts BOTH confirm with 200. */
    private fun fireConcurrentPatches(
        first: MessagingUser,
        firstPatch: Map<String, Any>,
        second: MessagingUser,
        secondPatch: Map<String, Any>,
        chatId: UUID,
    ) {
        val answers =
            patchConcurrently(
                listOf(
                    { patchGroup(first, chatId, firstPatch) },
                    { patchGroup(second, chatId, secondPatch) },
                ),
            )
        answers.forEach { answer ->
            assertThat(answer.statusCode)
                .overridingErrorMessage(
                    "both simultaneous №29 calls must confirm with 200 (serialized), got <%s>: %s",
                    answer.statusCode,
                    answer.body,
                ).isEqualTo(HttpStatus.OK)
        }
    }

    /** The stored №28 pair must be EXACTLY one whole confirmed patch — never a mix of the two. */
    private fun assertStoredWholePatch(
        reader: MessagingUser,
        chatId: UUID,
        wholePatches: List<Pair<String, String>>,
    ) {
        val view = objectMapper.readTree(getGroup(reader, chatId).body)
        val stored = view["title"].asText() to view["description"].asText()
        assertThat(stored)
            .overridingErrorMessage(
                "the last confirmed patch must land WHOLE — no mixing of the two, got <%s>",
                stored,
            ).isIn(*wholePatches.toTypedArray())
    }

    /**
     * Every active receives BOTH §3.1 frames — each repeating ONE whole
     * patch payload verbatim, the pair covering both confirmed patches.
     */
    private fun awaitWholePatchFrames(
        streams: List<UserEventsStream>,
        chatId: UUID,
        wholePatches: List<Pair<String, String>>,
    ) {
        streams.forEach { stream ->
            val frames =
                listOf(
                    stream.awaitEvent(GROUP_UPDATED_EVENT, deliveryBudget),
                    stream.awaitEvent(GROUP_UPDATED_EVENT, deliveryBudget),
                )
            frames.forEach { frame ->
                assertUpdatedFrameShape(frame, chatId)
                val payload = frame["title"].asText() to frame["description"].asText()
                assertThat(payload)
                    .overridingErrorMessage(
                        "every group.updated frame repeats ONE whole patch payload, got <%s>",
                        payload,
                    ).isIn(*wholePatches.toTypedArray())
            }
            assertThat(frames.map { it["title"].asText() }.toSet())
                .overridingErrorMessage(
                    "both confirmed patches broadcast to every active (§3.1), got titles <%s>",
                    frames.map { it["title"].asText() },
                ).isEqualTo(setOf(BOB_RENAME_TITLE, CAROL_RENAME_TITLE))
        }
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

    /** Contract №32 `DELETE /api/v1/groups/{chatId}/members/{userId}` — raw response. */
    private fun kickMember(
        user: MessagingUser,
        chatId: UUID,
        userId: UUID,
    ): ResponseEntity<String> = exchangeWithAuth(HttpMethod.DELETE, "$GROUPS_PATH/$chatId/members/$userId", user)

    /** Contract №34 `PUT /api/v1/groups/{chatId}/members/{userId}/role` — raw response. */
    private fun setMemberRole(
        user: MessagingUser,
        chatId: UUID,
        userId: UUID,
        role: String,
    ): ResponseEntity<String> =
        exchangeJsonAuthorized(
            HttpMethod.PUT,
            "$GROUPS_PATH/$chatId/members/$userId/role",
            mapOf(ROLE_FIELD to role),
            user,
        )

    /** Contract №35 `POST /api/v1/groups/{chatId}/owner` — raw response. */
    private fun transferOwnership(
        user: MessagingUser,
        chatId: UUID,
        userId: UUID,
    ): ResponseEntity<String> =
        postJsonAuthorized(
            "$GROUPS_PATH/$chatId/owner",
            mapOf(USER_ID_FIELD to userId.toString()),
            user,
        )

    /**
     * Contract №29 `PATCH /api/v1/groups/{chatId}` — raw response. The
     * call rides the JDK HttpClient on purpose: the build carries no
     * Apache HTTP client (plan.md VII) and RestTemplate's default JDK
     * factory refuses the PATCH method — the same deviation the SSE
     * reader of MessagingTestSupport records. The response is rebuilt as
     * a `ResponseEntity` (status + body + the served Content-Type), so
     * every shared assertion helper keeps working verbatim.
     */
    private fun patchGroup(
        user: MessagingUser,
        chatId: UUID,
        payload: Map<String, Any>,
    ): ResponseEntity<String> {
        val request =
            HttpRequest
                .newBuilder()
                .uri(URI.create("http://localhost:$serverPort$GROUPS_PATH/$chatId"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${user.accessToken}")
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .method(
                    PATCH_METHOD,
                    HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)),
                ).build()
        val response = patchHttpClient.send(request, HttpResponse.BodyHandlers.ofString())
        val headers =
            HttpHeaders().apply {
                response.headers().firstValue(HttpHeaders.CONTENT_TYPE).ifPresent { set(HttpHeaders.CONTENT_TYPE, it) }
            }
        return ResponseEntity.status(response.statusCode()).headers(headers).body(response.body())
    }

    /**
     * Fires every №29 call of [calls] through its own worker behind ONE
     * start gate — genuinely simultaneous PATCHes (the edge of
     * api-contract.md №29) — and returns the answers in the call order.
     */
    private fun patchConcurrently(calls: List<() -> ResponseEntity<String>>): List<ResponseEntity<String>> {
        val startGate = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(calls.size)
        try {
            val futures =
                calls.map { call ->
                    pool.submit<ResponseEntity<String>> {
                        assertThat(startGate.await(START_GATE_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                            .overridingErrorMessage("the concurrent №29 workers must start together")
                            .isTrue
                        call()
                    }
                }
            startGate.countDown()
            return futures.map { future -> future.get(PATCH_CONCURRENCY_BUDGET_SECONDS, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    /** №34 happy-path fixture: the owner grants the admin role to [userId]. */
    private fun grantAdminOk(
        owner: MessagingUser,
        chatId: UUID,
        userId: UUID,
    ) {
        val response = setMemberRole(owner, chatId, userId, ADMIN_ROLE)
        assertThat(response.statusCode)
            .overridingErrorMessage(
                "fixture №34 admin grant must answer 200, got <%s>: %s",
                response.statusCode,
                response.body,
            ).isEqualTo(HttpStatus.OK)
    }

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

    private fun exchangeJsonAuthorized(
        method: HttpMethod,
        path: String,
        payload: Map<String, Any>,
        user: MessagingUser,
    ): ResponseEntity<String> =
        restTemplate.exchange(
            path,
            method,
            HttpEntity(
                payload,
                HttpHeaders().apply {
                    contentType = MediaType.APPLICATION_JSON
                    setBearerAuth(user.accessToken)
                },
            ),
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

    /**
     * Every active stream must receive the `group.role.changed` frame of
     * one №34 change (§3.4: the broadcast goes to ALL actives).
     */
    private fun awaitRoleChangedOnAll(
        streams: List<UserEventsStream>,
        chatId: UUID,
        userId: UUID,
        role: String,
        actorId: UUID,
    ) {
        streams.forEach { stream ->
            assertRoleChangedFrame(
                stream.awaitEvent(GROUP_ROLE_CHANGED_EVENT, deliveryBudget),
                chatId = chatId,
                userId = userId,
                role = role,
                actorId = actorId,
            )
        }
    }

    /**
     * A `group.role.changed` frame must repeat the contract payload of
     * realtime-group-events.md §3.4 verbatim — field-by-field, no extras.
     */
    private fun assertRoleChangedFrame(
        event: JsonNode,
        chatId: UUID,
        userId: UUID,
        role: String,
        actorId: UUID,
    ) {
        assertThat(fieldNames(event))
            .overridingErrorMessage(
                "group.role.changed must carry exactly the contract fields, got <%s>",
                fieldNames(event),
            ).containsExactlyInAnyOrderElementsOf(GROUP_ROLE_CHANGED_EVENT_FIELDS)
        assertThat(event["groupId"].asText()).isEqualTo(chatId.toString())
        assertThat(event["userId"].asText()).isEqualTo(userId.toString())
        assertThat(event["role"].asText()).isEqualTo(role)
        assertThat(event["actorId"].asText()).isEqualTo(actorId.toString())
    }

    /** A `group.member.removed` frame of §3.3 — field-by-field, actorId is the kicker here. */
    private fun assertMemberRemovedFrame(
        event: JsonNode,
        chatId: UUID,
        userId: UUID,
        actorId: UUID,
    ) {
        assertThat(fieldNames(event))
            .overridingErrorMessage(
                "group.member.removed must carry exactly the contract fields, got <%s>",
                fieldNames(event),
            ).containsExactlyInAnyOrderElementsOf(GROUP_MEMBER_REMOVED_EVENT_FIELDS)
        assertThat(event["groupId"].asText()).isEqualTo(chatId.toString())
        assertThat(event["userId"].asText()).isEqualTo(userId.toString())
        assertThat(event["actorId"].asText()).isEqualTo(actorId.toString())
    }

    /**
     * Every active stream must receive the `group.updated` frame of one
     * №29 patch (§3.1: the broadcast goes to ALL actives, actor included).
     */
    private fun awaitUpdatedOnAll(
        streams: List<UserEventsStream>,
        chatId: UUID,
        title: String,
        description: String,
        actorId: UUID,
    ) {
        streams.forEach { stream ->
            assertUpdatedFrame(
                stream.awaitEvent(GROUP_UPDATED_EVENT, deliveryBudget),
                chatId = chatId,
                title = title,
                description = description,
                actorId = actorId,
            )
        }
    }

    /** A `group.updated` frame must repeat the §3.1 payload verbatim — field-by-field, no extras. */
    private fun assertUpdatedFrame(
        event: JsonNode,
        chatId: UUID,
        title: String,
        description: String,
        actorId: UUID,
    ) {
        assertUpdatedFrameShape(event, chatId)
        assertThat(event["title"].asText()).isEqualTo(title)
        assertThat(event["description"].asText()).isEqualTo(description)
        assertThat(event["actorId"].asText()).isEqualTo(actorId.toString())
    }

    /** The §3.1 wire shape + group identity (payload values asserted by the caller — the edge varies them). */
    private fun assertUpdatedFrameShape(
        event: JsonNode,
        chatId: UUID,
    ) {
        assertThat(fieldNames(event))
            .overridingErrorMessage(
                "group.updated must carry exactly the contract fields, got <%s>",
                fieldNames(event),
            ).containsExactlyInAnyOrderElementsOf(GROUP_UPDATED_EVENT_FIELDS)
        assertThat(event["groupId"].asText()).isEqualTo(chatId.toString())
    }

    /** The number of ACTIVE owner rows — the `ux_chat_participants_owner` invariant probe of №35. */
    private fun activeOwnerCount(chatId: UUID): Long =
        jdbcTemplate.queryForObject(
            """
            SELECT count(*) FROM chat_participants
            WHERE chat_id = ? AND role = 'owner' AND state = 'active'
            """.trimIndent(),
            Long::class.java,
            chatId,
        ) ?: 0L

    /** The group's journal entries of [actions] as `(action, actor)` pairs — the FR-017 probe of №29. */
    private fun adminLogActions(
        chatId: UUID,
        vararg actions: String,
    ): List<Pair<String, UUID>> =
        jdbcTemplate.query(
            """
            SELECT action, actor_id FROM group_admin_log
            WHERE group_id = ? AND action IN (${placeholders(actions.size)})
            """.trimIndent(),
            { rs, _ -> rs.getString("action") to UUID.fromString(rs.getString("actor_id")) },
            chatId,
            *actions,
        )

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
        const val ROLE_FIELD = "role"

        const val ADMIN_ROLE = "admin"
        const val MEMBER_ROLE = "member"
        const val OWNER_ROLE = "owner"

        /** A value no contract operation accepts — the `invalid_role` probe. */
        const val ALIEN_ROLE = "boss"

        const val INVALID_TITLE = "invalid_title"
        const val INVALID_DESCRIPTION = "invalid_description"
        const val NOT_IN_CONTACTS = "not_in_contacts"
        const val SELF_FORBIDDEN = "self_forbidden"
        const val FORBIDDEN_ROLE = "forbidden_role"
        const val GROUP_NOT_FOUND = "group_not_found"
        const val NOT_GROUP_OWNER = "not_group_owner"
        const val ROLE_HIERARCHY_VIOLATION = "role_hierarchy_violation"
        const val TARGET_NOT_MEMBER = "target_not_member"
        const val INVALID_ROLE = "invalid_role"
        const val EMPTY_PATCH = "empty_patch"

        /** api-contract.md №29: the empty-body refusal rides `errors.body`. */
        const val BODY_FIELD = "body"

        /** data-model.md §Сущность 4 — the №29 journal actions. */
        const val TITLE_CHANGED_ACTION = "title_changed"
        const val DESCRIPTION_CHANGED_ACTION = "description_changed"

        /** realtime-group-events.md §3 names — the №18 frames of the roles/metadata slices. */
        const val GROUP_ROLE_CHANGED_EVENT = "group.role.changed"
        const val GROUP_MEMBER_REMOVED_EVENT = "group.member.removed"
        const val GROUP_YOU_REMOVED_EVENT = "group.you_removed"
        const val GROUP_UPDATED_EVENT = "group.updated"
        const val KICKED_REASON = "kicked"

        /** A role frame must arrive well inside the CI-tolerant SC-004 budget. */
        const val DELIVERY_BUDGET_SECONDS = 5L
        const val KICK_SEEDED_MESSAGES = 1
        const val KICK_TEXT_PREFIX = "kick-target-message"

        /** US4 metadata fixtures. */
        const val RENAMED_TITLE = "US4 renamed title"
        const val RENAMED_DESCRIPTION = "the US4 replacement description"
        const val MEMBER_REJECTED_TITLE = "a member writes no titles"
        const val ATOMIC_REJECTED_TITLE = "nothing of this title may land"
        const val BOB_RENAME_TITLE = "bob's whole patch"
        const val BOB_RENAME_DESCRIPTION = "bob's whole description"
        const val CAROL_RENAME_TITLE = "carol's whole patch"
        const val CAROL_RENAME_DESCRIPTION = "carol's whole description"

        /**
         * The №29 JDK-HttpClient leg: RestTemplate's default JDK factory
         * refuses the PATCH method (no Apache client on the build — the
         * MessagingTestSupport SSE-reader deviation note).
         */
        const val PATCH_METHOD = "PATCH"
        const val PATCH_CONNECT_TIMEOUT_SECONDS = 5L
        const val START_GATE_TIMEOUT_SECONDS = 10L
        const val PATCH_CONCURRENCY_BUDGET_SECONDS = 30L

        fun placeholders(count: Int): String = List(count) { "?" }.joinToString(", ")

        /** openapi.yaml 0.6.0 shapes — every group answer is pinned field-by-field. */
        val GROUP_VIEW_FIELDS = listOf("chatId", "title", "description", "myRole", "members")
        val GROUP_MEMBER_FIELDS = listOf("user", "role", "joinedAt")
        val PUBLIC_USER_FIELDS = listOf("id", "username", "email", "status", "createdAt")
        val GROUP_ROLE_CHANGED_EVENT_FIELDS = listOf("groupId", "userId", "role", "actorId")
        val GROUP_MEMBER_REMOVED_EVENT_FIELDS = listOf("groupId", "userId", "actorId")
        val GROUP_YOU_REMOVED_EVENT_FIELDS = listOf("groupId", "reason")
        val GROUP_UPDATED_EVENT_FIELDS = listOf("groupId", "title", "description", "actorId")
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
