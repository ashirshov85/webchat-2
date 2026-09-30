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
import org.springframework.web.util.UriComponentsBuilder
import webchat.backend.sync.SyncTestSupport
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

/**
 * T054 (tasks.md Phase 7, US5, constitution VI Test-First) — the PRIVACY
 * MATRIX «операция × не-участник/несуществующая/исключённый» (SC-003)
 * over the real 001–006 flows, Testcontainers PG+Redis and real HTTP (no
 * mocks), every method staging its own users and contacts so nothing
 * leaks between methods:
 *
 *  * the 004 paths (№15/№16/№17/№25 with the chatId of a group) keep the
 *    004 refusal semantics verbatim (the FR-008 rider of api-contract.md
 *    §3): an unknown chatId answers `404 chat_not_found`; an existing
 *    group without an active membership row — a never-member AND a
 *    `state='removed'` row produced by a REAL №32 kick — answers `403
 *    not_participant`; and a №25 batch carrying a group chatId is
 *    refused ATOMICALLY (all-or-refusal 005, §3): the caller's own valid
 *    item of the same batch is not applied;
 *  * the enumeration paths carry no trace of the group (FR-009): №12
 *    `GET /chats` and №26 sync answer neither to a stranger nor to the
 *    removed member — no group element, no group delta (api-contract.md
 *    §3; the removed row is filtered by the active-membership rule);
 *  * the groups operations №28/№29/№31/№32/№34/№35 answer the ONE
 *    uniform `404 group_not_found` to a stranger, a removed member and
 *    an unknown chat id (api-contract.md §1) — existence is never
 *    disclosed (SC-003; the №33/№30 rows of the matrix join with T059
 *    of US6, №27 is never gated — the group does not exist yet);
 *  * №18 SSE: group events never reach the stream of a non-member — a
 *    stranger's stream stays silent while a member's stream receives
 *    every frame of the SAME activity (message.created, group.updated,
 *    group.member.added, group.role.changed — the positive control);
 *  * SC-006: after the №32 kick the removed member receives the FINAL
 *    `group.you_removed {reason:'kicked'}` inside the ≤5 s window and
 *    NO group frame follows it, while the survivors keep receiving the
 *    very same activity (realtime-group-events.md §1 — the logical
 *    subscription close);
 *  * the metric `webchat_group_authz_denials_total` grows with the
 *    matrix refusals — read the way an operator reads it, off the real
 *    `/actuator/prometheus` exposition (FR-017/SC-003; deltas, not
 *    absolutes, keep it immune to the shared test context);
 *  * №19 `GET /users/search` and №20 `GET /contacts` expose no groups
 *    and no memberships (US5-4/FR-009): the group is not discoverable
 *    by its title, and `PublicUser`/`ContactView` keep the exact 0.5.0
 *    field sets — 0.6.0 extends neither with group fields.
 *
 * NOTE (TDD, constitution VI): written as the closing gate of US5 — the
 * gate paths themselves landed with US1–US4 (T021/T022/T034/T037/T043/
 * T044/T052), so this class pins them END-TO-END; T056/T057 close any
 * gap it may find. The frontend privacy rules (№25 batch exclusion, the
 * «Чаты» removal without polling) are T055/T058 and are NOT retested
 * here; the №33/№30 matrix rows and `group.deleted` are T059–T066 of
 * US6.
 */
@Suppress("TooManyFunctions", "LargeClass") // T054: one method per US5 rule of tasks.md
class GroupPrivacyIT(
    @Autowired private val restTemplate: TestRestTemplate,
    @Autowired private val objectMapper: ObjectMapper,
) : SyncTestSupport() {
    /** A group frame must arrive well inside this CI-tolerant budget (SC-004 functional leg). */
    private val deliveryBudget: Duration = Duration.ofSeconds(DELIVERY_BUDGET_SECONDS)

    /** SC-006: the final `group.you_removed` must arrive inside this window after the removal. */
    private val sc006Budget: Duration = Duration.ofSeconds(SC006_BUDGET_SECONDS)

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

    /**
     * The 004 paths of api-contract.md §3 (the FR-008 rider): №15/№16/№17
     * keep the 004 refusal order on group ids and №25 is all-or-refusal —
     * an unknown chatId is `404 chat_not_found`, a never-member and a
     * REMOVED member (a real №32 kick) are `403 not_participant`, and a
     * refused №25 batch leaves no partial effects.
     */
    @Test
    fun `chat paths keep the 004 semantics for strangers the removed and unknown ids`() {
        val owner = messagingUser("owner")
        val bob = messagingUser("bob")
        val carol = messagingUser("carol")
        val dave = messagingUser("dave")
        addContact(owner, bob.id)
        val chatId = chatIdOf(createGroupOk(owner, memberUserIds = listOf(bob.id)))
        seedChatBacklog(chatId, SEEDED_MESSAGES, { owner }, GROUP_TEXT_PREFIX)
        val head = lastSeqOf(chatId)
        assertThat(head)
            .overridingErrorMessage("the fixture must seed the group above the zero head (valid №17/№25 bodies)")
            .isPositive
        val ownChat = ensureChatOk(carol, dave.id)
        val ownSeq = seedChatBacklog(ownChat, SEEDED_MESSAGES, { dave }, OWN_TEXT_PREFIX).last().seq
        val unknownChat = UUID.randomUUID()

        assertChatPathsRefused(owner, unknownChat, head, HttpStatus.NOT_FOUND, "an unknown chat id")
        assertChatPathsRefused(carol, chatId, head, HttpStatus.FORBIDDEN, "a never-member")
        assertAckBatchRefusedAtomically(carol, chatId, head, ownChat, ownSeq)

        val kick = kickMember(owner, chatId, bob.id)
        assertThat(kick.statusCode)
            .overridingErrorMessage(
                "fixture №32 kick staging must answer 204, got <%s>: %s",
                kick.statusCode,
                kick.body,
            ).isEqualTo(HttpStatus.NO_CONTENT)

        assertChatPathsRefused(bob, chatId, head, HttpStatus.FORBIDDEN, "a removed member")
        assertProblem(
            deliveryAck(bob, listOf(chatId to head)),
            HttpStatus.FORBIDDEN,
            CHAT_FIELD,
            NOT_PARTICIPANT,
            "№25 of a removed member",
        )
    }

    /**
     * The №15/№16/№17 refusal row of one [identity] — the 004 status of
     * api-contract.md §3 decides its code: `404 chat_not_found` for an
     * unknown chat id, `403 not_participant` for a missing/removed
     * membership row of an EXISTING chat.
     */
    private fun assertChatPathsRefused(
        caller: MessagingUser,
        chatId: UUID,
        head: Long,
        expected: HttpStatus,
        identity: String,
    ) {
        val code = if (expected == HttpStatus.NOT_FOUND) CHAT_NOT_FOUND else NOT_PARTICIPANT
        assertProblem(listMessages(caller, chatId), expected, CHAT_FIELD, code, "№15 of $identity")
        assertProblem(
            sendMessage(caller, chatId, STRANGER_PROBE_TEXT),
            expected,
            CHAT_FIELD,
            code,
            "№16 of $identity",
        )
        assertProblem(markRead(caller, chatId, head), expected, CHAT_FIELD, code, "№17 of $identity")
    }

    /**
     * The №25 all-or-refusal row of api-contract.md §3: a batch mixing
     * the caller's OWN valid item with the group chatId of his
     * non-membership is refused whole — no partial ack survives — and a
     * valid single ack still lands afterwards.
     */
    private fun assertAckBatchRefusedAtomically(
        caller: MessagingUser,
        chatId: UUID,
        head: Long,
        ownChat: UUID,
        ownSeq: Long,
    ) {
        val mixedBatch = deliveryAck(caller, listOf(ownChat to ownSeq, chatId to head))
        assertProblem(
            mixedBatch,
            HttpStatus.FORBIDDEN,
            CHAT_FIELD,
            NOT_PARTICIPANT,
            "№25 batch with a group chatId of a never-member",
        )
        assertThat(deliveredUpToSeq(caller.id, ownChat))
            .overridingErrorMessage("a refused №25 batch must leave NO partial effects — it is all-or-refusal (§3)")
            .isZero
        assertThat(deliveryAck(caller, listOf(ownChat to ownSeq)).statusCode)
            .overridingErrorMessage("after the refused batch a valid single ack must still land")
            .isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(deliveredUpToSeq(caller.id, ownChat)).isEqualTo(ownSeq)
    }

    /**
     * The enumeration paths of FR-009 (api-contract.md §3): №12 `GET
     * /chats` and №26 sync answer nothing of the group to a stranger and
     * to the removed member — no group element, no group delta; the
     * removed `state='removed'` row is filtered exactly like a missing
     * one, and the stranger's OWN dialog keeps flowing in the same
     * answers (the filter is membership-scoped, not user-scoped).
     */
    @Test
    fun `the chats list and sync carry no group trace for strangers and the removed`() {
        val owner = messagingUser("owner")
        val bob = messagingUser("bob")
        val carol = messagingUser("carol")
        val dave = messagingUser("dave")
        addContact(owner, bob.id)
        val chatId = chatIdOf(createGroupOk(owner, memberUserIds = listOf(bob.id)))
        seedChatBacklog(chatId, SEEDED_MESSAGES, { owner }, GROUP_TEXT_PREFIX)
        val ownChat = ensureChatOk(carol, dave.id)
        seedChatBacklog(ownChat, SEEDED_MESSAGES, { dave }, OWN_TEXT_PREFIX)

        val strangerList = chatIdsOf(listChats(carol))
        assertThat(strangerList)
            .overridingErrorMessage(
                "№12 of a never-member must carry only his own dialog — never the group (FR-009), got <%s>",
                strangerList,
            ).containsExactly(ownChat.toString())

        val strangerSync = requestSync(carol, listOf(chatId to ZERO_CURSOR, ownChat to ZERO_CURSOR))
        assertThat(strangerSync.statusCode)
            .overridingErrorMessage(
                "№26 of a never-member must answer 200, got <%s>: %s",
                strangerSync.statusCode,
                strangerSync.body,
            ).isEqualTo(HttpStatus.OK)
        assertThat(deltaChatIdsOf(strangerSync))
            .overridingErrorMessage(
                "№26 must deliver NO group delta to a never-member (api-contract.md §3), got <%s>",
                deltaChatIdsOf(strangerSync),
            ).containsExactly(ownChat.toString())

        val kick = kickMember(owner, chatId, bob.id)
        assertThat(kick.statusCode)
            .overridingErrorMessage(
                "fixture №32 kick staging must answer 204, got <%s>: %s",
                kick.statusCode,
                kick.body,
            ).isEqualTo(HttpStatus.NO_CONTENT)

        val removedList = chatIdsOf(listChats(bob))
        assertThat(removedList)
            .overridingErrorMessage(
                "№12 must DROP the group of a removed member entirely (FR-008/FR-009), got <%s>",
                removedList,
            ).isEmpty()

        val removedSync = requestSync(bob, listOf(chatId to ZERO_CURSOR))
        assertThat(removedSync.statusCode)
            .overridingErrorMessage(
                "№26 of a removed member must answer 200, got <%s>: %s",
                removedSync.statusCode,
                removedSync.body,
            ).isEqualTo(HttpStatus.OK)
        assertThat(deltaChatIdsOf(removedSync))
            .overridingErrorMessage(
                "№26 must deliver NO group delta to a removed member — the state='removed' row is filtered (§3)",
                deltaChatIdsOf(removedSync),
            ).isEmpty()
    }

    /**
     * The groups matrix of api-contract.md §1 (SC-003): every operation
     * №28/№29/№31/№32/№34/№35 answers the ONE uniform `404
     * group_not_found` to a stranger, a removed member and an unknown
     * chat id — the membership gate runs before any role distinction,
     * so existence is never disclosed (FR-008/FR-009). №33/№30 join the
     * matrix with T059 (US6); №27 is never gated.
     */
    @Test
    fun `groups operations answer the uniform 404 to strangers the removed and unknown ids`() {
        val owner = messagingUser("owner")
        val bob = messagingUser("bob")
        val carol = messagingUser("carol")
        val dave = messagingUser("dave")
        addContact(owner, bob.id)
        val chatId = chatIdOf(createGroupOk(owner, memberUserIds = listOf(bob.id)))
        val kick = kickMember(owner, chatId, bob.id)
        assertThat(kick.statusCode)
            .overridingErrorMessage(
                "fixture №32 kick staging must answer 204, got <%s>: %s",
                kick.statusCode,
                kick.body,
            ).isEqualTo(HttpStatus.NO_CONTENT)
        val unknownChat = UUID.randomUUID()

        assertGroupsMatrixRefused(carol, chatId, dave.id, "a stranger")
        assertGroupsMatrixRefused(bob, chatId, dave.id, "a removed member")
        assertGroupsMatrixRefused(owner, unknownChat, dave.id, "an unknown chat id")
    }

    /**
     * The six gated operations of the matrix row [identity] — every body
     * is valid, so the caller's membership is the ONLY refusal reason.
     */
    private fun assertGroupsMatrixRefused(
        caller: MessagingUser,
        chatId: UUID,
        targetId: UUID,
        identity: String,
    ) {
        assertGroupNotFound(getGroup(caller, chatId), "№28 get by $identity")
        assertGroupNotFound(patchGroup(caller, chatId, mapOf(TITLE_FIELD to RENAMED_TITLE)), "№29 patch by $identity")
        assertGroupNotFound(addGroupMembers(caller, chatId, listOf(targetId)), "№31 add members by $identity")
        assertGroupNotFound(kickMember(caller, chatId, targetId), "№32 kick by $identity")
        assertGroupNotFound(setMemberRole(caller, chatId, targetId, ADMIN_ROLE), "№34 set role by $identity")
        assertGroupNotFound(transferOwnership(caller, chatId, targetId), "№35 transfer by $identity")
    }

    /**
     * №18 of a non-member (FR-009): while a member's stream receives
     * every frame of the group activity (the positive control — №16
     * send, №29 patch, №31 add, №34 role grant), the stranger's stream
     * receives NO group frame at all — neither the events nor the
     * message fanout carry the group's chatId into his flow.
     */
    @Test
    fun `group events never reach the stream of a stranger`() {
        val owner = messagingUser("owner")
        val bob = messagingUser("bob")
        val carol = messagingUser("carol")
        val dave = messagingUser("dave")
        addContact(owner, bob.id)
        addContact(owner, dave.id)
        val chatId = chatIdOf(createGroupOk(owner, memberUserIds = listOf(bob.id)))

        openUserEvents(carol).use { strangerStream ->
            openUserEvents(bob).use { memberStream ->
                sendMessageOk(owner, chatId, STRANGER_PROBE_TEXT)
                assertThat(memberStream.awaitEvent(MESSAGE_CREATED_EVENT, deliveryBudget)["chatId"].asText())
                    .isEqualTo(chatId.toString())

                val patch = patchGroup(owner, chatId, mapOf(TITLE_FIELD to RENAMED_TITLE))
                assertThat(patch.statusCode)
                    .overridingErrorMessage(
                        "fixture №29 patch must answer 200, got <%s>: %s",
                        patch.statusCode,
                        patch.body,
                    ).isEqualTo(HttpStatus.OK)
                assertThat(memberStream.awaitEvent(GROUP_UPDATED_EVENT, deliveryBudget)["groupId"].asText())
                    .isEqualTo(chatId.toString())

                val added = addGroupMembers(owner, chatId, listOf(dave.id))
                assertThat(added.statusCode)
                    .overridingErrorMessage(
                        "fixture №31 add must answer 200, got <%s>: %s",
                        added.statusCode,
                        added.body,
                    ).isEqualTo(HttpStatus.OK)
                assertThat(memberStream.awaitEvent(GROUP_MEMBER_ADDED_EVENT, deliveryBudget)["groupId"].asText())
                    .isEqualTo(chatId.toString())

                val role = setMemberRole(owner, chatId, dave.id, ADMIN_ROLE)
                assertThat(role.statusCode)
                    .overridingErrorMessage(
                        "fixture №34 role grant must answer 200, got <%s>: %s",
                        role.statusCode,
                        role.body,
                    ).isEqualTo(HttpStatus.OK)
                assertThat(memberStream.awaitEvent(GROUP_ROLE_CHANGED_EVENT, deliveryBudget)["groupId"].asText())
                    .isEqualTo(chatId.toString())

                assertNoGroupFramesReach(strangerStream, chatId, NO_FRAME_WINDOW)
            }
        }
    }

    /**
     * SC-006 (FR-010, realtime-group-events.md §1): the №32 kick closes
     * the removed member's group subscription — `group.you_removed
     * {reason:'kicked'}` is his FINAL group frame and arrives inside the
     * ≤5 s window; the post-removal activity (№16 sends of the owner and
     * a fellow member, a №29 patch) reaches the SURVIVORS verbatim while
     * NOTHING of the group follows on the removed member's stream.
     */
    @Test
    fun `you removed is the final group frame and nothing follows within the window`() {
        val owner = messagingUser("owner")
        val bob = messagingUser("bob")
        val carol = messagingUser("carol")
        addContact(owner, bob.id)
        addContact(owner, carol.id)
        val chatId = chatIdOf(createGroupOk(owner, memberUserIds = listOf(bob.id, carol.id)))

        openUserEvents(bob).use { removedStream ->
            openUserEvents(carol).use { survivorStream ->
                openUserEvents(owner).use { ownerStream ->
                    val kick = kickMember(owner, chatId, bob.id)
                    assertThat(kick.statusCode)
                        .overridingErrorMessage(
                            "fixture №32 kick staging must answer 204, got <%s>: %s",
                            kick.statusCode,
                            kick.body,
                        ).isEqualTo(HttpStatus.NO_CONTENT)

                    val finalFrame = removedStream.awaitEvent(GROUP_YOU_REMOVED_EVENT, sc006Budget)
                    assertThat(finalFrame["groupId"].asText()).isEqualTo(chatId.toString())
                    assertThat(finalFrame["reason"].asText())
                        .overridingErrorMessage("a №32 removal is the 'kicked' reason (§3.6)")
                        .isEqualTo(KICKED_REASON)

                    sendMessageOk(owner, chatId, POST_REMOVAL_OWNER_TEXT)
                    assertThat(survivorStream.awaitEvent(MESSAGE_CREATED_EVENT, deliveryBudget)["chatId"].asText())
                        .isEqualTo(chatId.toString())

                    val patch = patchGroup(owner, chatId, mapOf(TITLE_FIELD to RENAMED_TITLE))
                    assertThat(patch.statusCode)
                        .overridingErrorMessage(
                            "fixture №29 patch must answer 200, got <%s>: %s",
                            patch.statusCode,
                            patch.body,
                        ).isEqualTo(HttpStatus.OK)
                    assertThat(survivorStream.awaitEvent(GROUP_UPDATED_EVENT, deliveryBudget)["groupId"].asText())
                        .isEqualTo(chatId.toString())

                    sendMessageOk(carol, chatId, POST_REMOVAL_MEMBER_TEXT)
                    assertThat(ownerStream.awaitEvent(MESSAGE_CREATED_EVENT, deliveryBudget)["chatId"].asText())
                        .isEqualTo(chatId.toString())

                    assertNoGroupFramesReach(removedStream, chatId, NO_FRAME_WINDOW)
                }
            }
        }
    }

    /**
     * FR-017/SC-003, observability leg: the matrix refusals grow
     * `webchat_group_authz_denials_total` — scraped off the REAL
     * `/actuator/prometheus` exposition before/after the calls (deltas,
     * not absolutes), split per the `operation` tag the way the gate
     * counts them (№28 → `get` here, exactly the three №28 refusals).
     */
    @Test
    fun `the authz denial counter grows with the groups refusals`() {
        val owner = messagingUser("owner")
        val bob = messagingUser("bob")
        val carol = messagingUser("carol")
        val dave = messagingUser("dave")
        addContact(owner, bob.id)
        val chatId = chatIdOf(createGroupOk(owner, memberUserIds = listOf(bob.id)))
        val kick = kickMember(owner, chatId, bob.id)
        assertThat(kick.statusCode)
            .overridingErrorMessage(
                "fixture №32 kick staging must answer 204, got <%s>: %s",
                kick.statusCode,
                kick.body,
            ).isEqualTo(HttpStatus.NO_CONTENT)
        val unknownChat = UUID.randomUUID()

        val totalBefore = authzDenialsTotal()
        val getBefore = authzDenialsOfOperation(GET_OPERATION)

        assertGroupNotFound(getGroup(carol, chatId), "№28 by a stranger")
        assertGroupNotFound(getGroup(bob, chatId), "№28 by a removed member")
        assertGroupNotFound(getGroup(owner, unknownChat), "№28 of an unknown chat id")
        assertGroupNotFound(patchGroup(carol, chatId, mapOf(TITLE_FIELD to RENAMED_TITLE)), "№29 by a stranger")
        assertGroupNotFound(addGroupMembers(bob, chatId, listOf(dave.id)), "№31 by a removed member")
        assertGroupNotFound(kickMember(owner, unknownChat, dave.id), "№32 of an unknown chat id")
        assertGroupNotFound(setMemberRole(carol, chatId, dave.id, ADMIN_ROLE), "№34 by a stranger")
        assertGroupNotFound(transferOwnership(bob, chatId, dave.id), "№35 by a removed member")

        assertThat(authzDenialsTotal() - totalBefore)
            .overridingErrorMessage(
                "webchat_group_authz_denials_total must grow by the %d matrix refusals (FR-017/SC-003), " +
                    "delta=<%s>",
                MATRIX_REFUSALS,
                authzDenialsTotal() - totalBefore,
            ).isGreaterThanOrEqualTo(MATRIX_REFUSALS.toDouble())
        assertThat(authzDenialsOfOperation(GET_OPERATION) - getBefore)
            .overridingErrorMessage(
                "the operation=\"%s\" sample must count exactly the %d №28 refusals, delta=<%s>",
                GET_OPERATION,
                GET_REFUSALS,
                authzDenialsOfOperation(GET_OPERATION) - getBefore,
            ).isEqualTo(GET_REFUSALS.toDouble())
    }

    /**
     * US5-4/FR-009, the directories: №19 `GET /users/search` never
     * discovers a group — the exact title query misses cleanly — and №20
     * `GET /contacts` answers only `ContactView`s; both keep the exact
     * 0.5.0 field sets (`PublicUser` is NOT extended with group fields
     * in 0.6.0), and neither raw answer carries the group's id, title or
     * any membership projection.
     */
    @Test
    fun `user search and contacts expose no groups or memberships`() {
        val owner = messagingUser("owner")
        val bob = messagingUser("bob")
        addContact(owner, bob.id)
        val title = "$SEARCHABLE_TITLE_PREFIX${UUID.randomUUID()}"
        val chatId = chatIdOf(createGroupOk(owner, title, listOf(bob.id)))

        assertSearchByTitleMisses(owner, title, chatId)
        assertSearchByLoginKeepsPublicUser(owner, bob, title, chatId)
        assertContactsKeepContactView(owner, bob, title, chatId)
    }

    /** №19 by the exact group title — a clean miss carrying no group trace (US5-4/FR-009). */
    private fun assertSearchByTitleMisses(
        owner: MessagingUser,
        title: String,
        chatId: UUID,
    ) {
        val byTitle = searchUsers(owner, title)
        assertThat(byTitle.statusCode)
            .overridingErrorMessage(
                "№19 by a group title must answer 200 as a clean miss, got <%s>: %s",
                byTitle.statusCode,
                byTitle.body,
            ).isEqualTo(HttpStatus.OK)
        assertThat(objectMapper.readTree(byTitle.body)["users"].toList())
            .overridingErrorMessage("№19 must never discover a group by its title (US5-4/FR-009)")
            .isEmpty()
        assertNoGroupProjections(byTitle.body, title, chatId)
    }

    /** №19 by the exact login still finds the user, with PublicUser NOT extended by 0.6.0 (US5-4). */
    private fun assertSearchByLoginKeepsPublicUser(
        owner: MessagingUser,
        bob: MessagingUser,
        title: String,
        chatId: UUID,
    ) {
        val byLogin = searchUsers(owner, bob.username)
        assertThat(byLogin.statusCode).isEqualTo(HttpStatus.OK)
        val found = objectMapper.readTree(byLogin.body)["users"].toList()
        assertThat(found)
            .overridingErrorMessage("№19 by the exact login must still find the user himself")
            .hasSize(1)
        assertThat(found.single()["id"].asText()).isEqualTo(bob.id.toString())
        assertThat(fieldNames(found.single()))
            .overridingErrorMessage(
                "PublicUser keeps the exact 0.5.0 field set — 0.6.0 extends it with NO group field (US5-4), got <%s>",
                fieldNames(found.single()),
            ).containsExactlyInAnyOrderElementsOf(PUBLIC_USER_FIELDS)
        assertNoGroupProjections(byLogin.body, title, chatId)
    }

    /** №20 answers ContactView/PublicUser verbatim with no membership projections (US5-4/FR-009). */
    private fun assertContactsKeepContactView(
        owner: MessagingUser,
        bob: MessagingUser,
        title: String,
        chatId: UUID,
    ) {
        val contacts = listContacts(owner)
        assertThat(contacts.statusCode)
            .overridingErrorMessage(
                "№20 of a group owner must answer 200, got <%s>: %s",
                contacts.statusCode,
                contacts.body,
            ).isEqualTo(HttpStatus.OK)
        val items = objectMapper.readTree(contacts.body)["contacts"].toList()
        assertThat(items.map { it["user"]["id"].asText() })
            .overridingErrorMessage("№20 lists the contacts only — the roster membership adds nothing here")
            .containsExactly(bob.id.toString())
        items.forEach { item ->
            assertThat(fieldNames(item))
                .overridingErrorMessage(
                    "ContactView keeps the exact 0.5.0 field set (US5-4), got <%s>",
                    fieldNames(item),
                ).containsExactlyInAnyOrderElementsOf(CONTACT_VIEW_FIELDS)
            assertThat(fieldNames(item["user"]))
                .overridingErrorMessage("the contact fragment must reuse the unextended PublicUser schema")
                .containsExactlyInAnyOrderElementsOf(PUBLIC_USER_FIELDS)
        }
        assertNoGroupProjections(contacts.body, title, chatId)
    }

    /** Neither id, nor title, nor any group field name may appear in a №19/№20 raw answer. */
    private fun assertNoGroupProjections(
        body: String?,
        title: String,
        chatId: UUID,
    ) {
        assertThat(body.orEmpty())
            .overridingErrorMessage("the answer must carry no group or membership traces (FR-009)")
            .doesNotContain(chatId.toString())
            .doesNotContain(title)
            .doesNotContain(MY_ROLE_FIELD)
            .doesNotContain(MEMBER_COUNT_FIELD)
            .doesNotContain(GROUP_ID_FIELD)
    }

    // ------------------------------------------------------------------
    // contract calls (raw responses for status/problem assertions)
    // ------------------------------------------------------------------

    /** Contract №27 `POST /api/v1/groups` — raw response. */
    private fun createGroup(
        owner: MessagingUser,
        title: String,
        memberUserIds: List<UUID> = emptyList(),
    ): ResponseEntity<String> =
        postJsonAuthorized(
            GROUPS_PATH,
            buildMap {
                put(TITLE_FIELD, title)
                if (memberUserIds.isNotEmpty()) put(MEMBER_USER_IDS_FIELD, memberUserIds.map(UUID::toString))
            },
            owner,
        )

    /** №27 happy path → the stored `GroupView` body. */
    private fun createGroupOk(
        owner: MessagingUser,
        title: String = GROUP_TITLE,
        memberUserIds: List<UUID> = emptyList(),
    ): JsonNode {
        val response = createGroup(owner, title, memberUserIds)
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
     * factory refuses the PATCH method — the same deviation the №29 leg
     * of GroupLifecycleIT records. The response is rebuilt as a
     * `ResponseEntity` (status + body + the served Content-Type), so the
     * shared assertion helpers keep working verbatim.
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

    /** Contract №21 `POST /api/v1/contacts` — fixture contact for the №27 roster gate. */
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

    /** Contract №19 `GET /api/v1/users/search?query=` — raw response. */
    private fun searchUsers(
        user: MessagingUser,
        query: String,
    ): ResponseEntity<String> =
        exchangeWithAuth(
            HttpMethod.GET,
            UriComponentsBuilder
                .fromPath(SEARCH_PATH)
                .queryParam(QUERY_FIELD, query)
                .build()
                .toUriString(),
            user,
        )

    /** Contract №20 `GET /api/v1/contacts` — raw response. */
    private fun listContacts(user: MessagingUser): ResponseEntity<String> = exchangeWithAuth(HttpMethod.GET, CONTACTS_PATH, user)

    // ------------------------------------------------------------------
    // probes
    // ------------------------------------------------------------------

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

    /** The caller's №12 chat ids — the enumeration probe of the group's absence. */
    private fun chatIdsOf(response: ResponseEntity<String>): List<String> {
        assertThat(response.statusCode)
            .overridingErrorMessage("№12 must answer 200, got <%s>: %s", response.statusCode, response.body)
            .isEqualTo(HttpStatus.OK)
        return objectMapper.readTree(response.body)["chats"].toList().map { it["chatId"].asText() }
    }

    /** The caller's №26 delta chat ids — the sync enumeration probe of the group's absence. */
    private fun deltaChatIdsOf(response: ResponseEntity<String>): List<String> =
        objectMapper
            .readTree(response.body)["chats"]
            .toList()
            .map { it["chatId"].asText() }

    private fun fieldNames(node: JsonNode): List<String> = node.fieldNames().asSequence().toList()

    /** RFC 9457 refusal probe: [expected] status with `errors.{field}=[code]` verbatim (convention 002). */
    private fun assertProblem(
        response: ResponseEntity<String>,
        expected: HttpStatus,
        field: String,
        code: String,
        context: String,
    ) {
        assertThat(response.statusCode)
            .overridingErrorMessage(
                "%s must answer %s with errors.%s=[%s], got <%s>: %s",
                context,
                expected,
                field,
                code,
                response.statusCode,
                response.body,
            ).isEqualTo(expected)
        assertThat(response.headers.contentType?.toString())
            .overridingErrorMessage("%s must be RFC 9457 application/problem+json", context)
            .contains(PROBLEM_JSON_MEDIA_TYPE)
        val codes =
            objectMapper
                .readTree(response.body)["errors"]
                ?.get(field)
                ?.map { it.asText() }
                .orEmpty()
        assertThat(codes)
            .overridingErrorMessage(
                "%s must carry errors.%s=[%s], got <%s> in %s",
                context,
                field,
                code,
                codes,
                response.body,
            ).containsExactly(code)
    }

    /** The uniform groups refusal of api-contract.md §1: `404` problem `errors: {group: [group_not_found]}`. */
    private fun assertGroupNotFound(
        response: ResponseEntity<String>,
        context: String,
    ) {
        assertThat(response.statusCode)
            .overridingErrorMessage(
                "%s must be refused with the uniform 404 (existence never disclosed), got <%s>: %s",
                context,
                response.statusCode,
                response.body,
            ).isEqualTo(HttpStatus.NOT_FOUND)
        assertProblem(response, HttpStatus.NOT_FOUND, GROUP_FIELD, GROUP_NOT_FOUND, context)
    }

    /**
     * Consumes frames for [window] and fails the moment a frame whose
     * payload mentions the group [chatId] shows up — the №18 silence
     * probe for non-member streams (any group event, `message.created`
     * and `chat.read` included, carries that id). A per-frame timeout
     * inside the window is the expected clean exit (nothing was
     * published): heartbeats and unrelated frames pass through freely.
     */
    private fun assertNoGroupFramesReach(
        stream: UserEventsStream,
        chatId: UUID,
        window: Duration,
    ) {
        val deadline = System.nanoTime() + window.toNanos()
        while (true) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return
            val frame =
                try {
                    stream.nextFrame(Duration.ofNanos(remaining))
                        ?: throw AssertionError(
                            "the stream ended while asserting that no frame of the group <$chatId> may follow",
                        )
                } catch (failure: AssertionError) {
                    if (TIMEOUT_MARKER !in failure.message.orEmpty()) throw failure
                    return
                }
            if (frame.data != null) {
                assertThat(frame.data)
                    .overridingErrorMessage(
                        "no frame of the group <%s> may reach a non-member stream, got event=<%s> data=<%s>",
                        chatId,
                        frame.event,
                        frame.data,
                    ).doesNotContain(chatId.toString())
            }
        }
    }

    // --- the operator's view of the authz counter (FR-017/SC-003) ---

    /** GET /actuator/prometheus — the real text exposition. */
    private fun scrapePrometheus(): String {
        val response = restTemplate.getForEntity(PROMETHEUS_PATH, String::class.java)
        assertThat(response.statusCode)
            .overridingErrorMessage("the prometheus exposition must answer 200, got <%s>", response.statusCode)
            .isEqualTo(HttpStatus.OK)
        return response.body.orEmpty()
    }

    /** The summed `webchat_group_authz_denials_total` over every `operation` tag. */
    private fun authzDenialsTotal(): Double = authzSamples().sumOf { it.third }

    /** The `webchat_group_authz_denials_total{operation="…"}` sample of one operation. */
    private fun authzDenialsOfOperation(operation: String): Double =
        authzSamples()
            .filter { it.second.contains("operation=\"$operation\"") }
            .sumOf { it.third }

    /** Every exposition sample of the authz counter as `(name, labels, value)`. */
    private fun authzSamples(): List<Triple<String, String, Double>> =
        scrapePrometheus()
            .lineSequence()
            .mapNotNull { line -> SAMPLE_PATTERN.matchEntire(line.trim()) }
            .filter { match -> match.groupValues[SAMPLE_NAME_GROUP] == AUTHZ_DENIALS_TOTAL }
            .map { match ->
                Triple(
                    match.groupValues[SAMPLE_NAME_GROUP],
                    match.groupValues[SAMPLE_LABELS_GROUP],
                    match.groupValues[SAMPLE_VALUE_GROUP].toDouble(),
                )
            }.toList()

    private companion object {
        const val GROUPS_PATH = "/api/v1/groups"
        const val CONTACTS_PATH = "/api/v1/contacts"
        const val SEARCH_PATH = "/api/v1/users/search"
        const val PROMETHEUS_PATH = "/actuator/prometheus"

        const val PROBLEM_JSON_MEDIA_TYPE = "application/problem+json"

        const val TITLE_FIELD = "title"
        const val MEMBER_USER_IDS_FIELD = "memberUserIds"
        const val USER_IDS_FIELD = "userIds"
        const val USER_ID_FIELD = "userId"
        const val GROUP_FIELD = "group"
        const val ROLE_FIELD = "role"
        const val QUERY_FIELD = "query"
        const val CHAT_FIELD = "chat"

        /** Group-projection field names that must NEVER appear in №19/№20 answers (US5-4). */
        const val MY_ROLE_FIELD = "myRole"
        const val MEMBER_COUNT_FIELD = "memberCount"
        const val GROUP_ID_FIELD = "groupId"

        const val ADMIN_ROLE = "admin"

        const val CHAT_NOT_FOUND = "chat_not_found"
        const val NOT_PARTICIPANT = "not_participant"
        const val GROUP_NOT_FOUND = "group_not_found"

        /** realtime-group-events.md §3 names — the №18 frames of the privacy probes. */
        const val MESSAGE_CREATED_EVENT = "message.created"
        const val GROUP_UPDATED_EVENT = "group.updated"
        const val GROUP_MEMBER_ADDED_EVENT = "group.member.added"
        const val GROUP_ROLE_CHANGED_EVENT = "group.role.changed"
        const val GROUP_YOU_REMOVED_EVENT = "group.you_removed"
        const val KICKED_REASON = "kicked"

        /** research.md §8 observability contract name (FR-017, SC-003). */
        const val AUTHZ_DENIALS_TOTAL = "webchat_group_authz_denials_total"

        /** GroupMetrics.AuthzOperation.GET — the №28 tag of the counter. */
        const val GET_OPERATION = "get"

        const val GROUP_TITLE = "US5 privacy matrix group"
        const val RENAMED_TITLE = "US5 privacy renamed"

        /** A token-shaped unique title — a legal №19 query that must always miss (US5-4). */
        const val SEARCHABLE_TITLE_PREFIX = "us5-privacy-group-"

        const val GROUP_TEXT_PREFIX = "privacy-group"
        const val OWN_TEXT_PREFIX = "privacy-own"
        const val STRANGER_PROBE_TEXT = "a valid body — membership is the only refusal reason"
        const val POST_REMOVAL_OWNER_TEXT = "the owner writes after the removal — survivors only"
        const val POST_REMOVAL_MEMBER_TEXT = "a survivor answers after the removal"

        const val SEEDED_MESSAGES = 2
        const val ZERO_CURSOR = 0L
        const val MATRIX_REFUSALS = 8
        const val GET_REFUSALS = 3

        /** SC-004 functional leg / SC-006 delivery window (CI-tolerant budgets). */
        const val DELIVERY_BUDGET_SECONDS = 5L
        const val SC006_BUDGET_SECONDS = 5L

        /** A leaked group push would land well inside the ≤2 s wire budget — 3 s is CI-tolerant. */
        val NO_FRAME_WINDOW: Duration = Duration.ofSeconds(3)
        const val TIMEOUT_MARKER = "timed out"

        /**
         * The №29 JDK-HttpClient leg: RestTemplate's default JDK factory
         * refuses the PATCH method (no Apache client on the build — the
         * GroupLifecycleIT deviation note).
         */
        const val PATCH_METHOD = "PATCH"
        const val PATCH_CONNECT_TIMEOUT_SECONDS = 5L

        /** openapi.yaml 0.5.0 shapes №19/№20 keep verbatim in 0.6.0 (US5-4). */
        val PUBLIC_USER_FIELDS = listOf("id", "username", "email", "status", "createdAt")
        val CONTACT_VIEW_FIELDS = listOf("user", "createdAt")

        /**
         * One exposition sample line: `name{labels} value` (the labels
         * braces may be absent; the authz counter always carries at least
         * the `application` common tag).
         */
        val SAMPLE_PATTERN = Regex("""([a-zA-Z_:][a-zA-Z0-9_:]*)(?:\{([^}]*)\})?\s+([0-9.eE+-]+)""")
        const val SAMPLE_NAME_GROUP = 1
        const val SAMPLE_LABELS_GROUP = 2
        const val SAMPLE_VALUE_GROUP = 3
    }
}
