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
import webchat.backend.groups.domain.port.GroupAdminAction
import webchat.backend.groups.domain.port.GroupAdminLogEntry
import webchat.backend.groups.domain.port.GroupAdminLogRepository
import webchat.backend.sync.SyncTestSupport
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * T060 (tasks.md Phase 8, US6, constitution VI Test-First) — the
 * FR-017 audit slice of the group admin journal over the REAL №27–№35
 * flows, Testcontainers PG+Redis and real HTTP (no mocks), every method
 * staging its own users and contacts so nothing leaks between methods:
 *
 *  * the CHRONOLOGY of `group_admin_log` mirrors the executed scenario
 *    EXACTLY — one method drives the whole group lifecycle through the
 *    public operations (№27 create with an initial roster, №34 grant +
 *    revoke, №29 title + description patches, №35 transfer, №32 kick,
 *    №31 re-add of the removed member, the idempotent №31 no-op, №33
 *    leave, №30 delete) and asserts the journal reads back as the exact
 *    `(action, actor, target)` timeline, oldest first, covering ALL TEN
 *    actions of the V14 CHECK `ck_group_admin_log_action`:
 *    `group_created`, `title_changed`, `description_changed`,
 *    `member_added`, `member_removed`, `member_left`, `admin_granted`,
 *    `admin_revoked`, `ownership_transferred`, `group_deleted`
 *    (data-model.md 006 §Сущность 4);
 *  * an IDEMPOTENT №31 re-add of an already-active member journals
 *    NOTHING — the journal records facts, not attempts;
 *  * the entries SURVIVE the №30 hard delete of their group (FR-006):
 *    the table deliberately carries NO FK on `chats`, so after the
 *    `chats`/`messages`/`chat_participants` rows are CASCADE-erased the
 *    journal still reads back WHOLE plus the final `group_deleted` fact
 *    WITHOUT CONTENT — `target_user_id` NULL and no payload: the
 *    deletion journals the FACT, never the deleted content (clarify
 *    2026-09-25, FR-017).
 *
 * The read rides the [GroupAdminLogRepository.chronology] port on
 * purpose — its audit-read contract explicitly serves this IT — plus a
 * RAW SQL probe against the table itself for the survival leg, so
 * FR-006 is pinned on the storage, not on the adapter.
 *
 * NOTE (TDD, constitution VI): written BEFORE the US6 implementation
 * tasks — until T063/T064/T065 land, №33 answers the plain Spring 404
 * and №30 the 405 of a missing handler, so both methods fail (RED) by
 * design (T063–T065 acceptance is this class green). The №27–№35 HTTP
 * semantics stay with GroupLifecycleIT (T018/T040/T049/T059) and the
 * adapter thin slice (append/chronology shape, per-group scoping) with
 * GroupRepositoryIT (T012a); everything HERE is the scenario-level
 * timeline. Keep every fixture self-contained per method.
 */
@Suppress("TooManyFunctions", "LargeClass") // T060: one helper per scenario operation of №27–№35
class GroupAdminLogIT(
    @Autowired private val restTemplate: TestRestTemplate,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val adminLog: GroupAdminLogRepository,
) : SyncTestSupport() {
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
     * The FULL-LIFECYCLE chronology (FR-017, data-model.md 006 §Сущность 4):
     * one scenario drives every administrative operation of the contract
     * in a deterministic order and the journal must read back as EXACTLY
     * that timeline — WHO did WHAT to WHOM, oldest first:
     *
     *  * №27 with the initial roster — `group_created` (target NULL) +
     *    `member_added` per initial member, all actor=creator;
     *  * №34 grant then revoke — `admin_granted`/`admin_revoked` with the
     *    target member;
     *  * №29 title then description patches — `title_changed`/
     *    `description_changed` with target NULL (metadata facts);
     *  * №35 transfer — `ownership_transferred` with the promoted target;
     *  * №32 kick by the NEW owner — `member_removed` with the kicked
     *    target;
     *  * №31 re-add of the removed member — `member_added` again (the
     *    reactivation is a genuine add, FR-002), while an IDEMPOTENT №31
     *    of the already-active member journals NOTHING;
     *  * №33 leave — `member_left` (actor = target = the leaver);
     *  * №30 delete by the owner — `group_deleted` (target NULL).
     *
     * All TEN actions of the V14 CHECK must appear, and every
     * `created_at` is the SERVER-stamped chronology coordinate (never the
     * EPOCH sentinel of a freshly built entry), non-decreasing in the
     * read order — the `(group_id, created_at)` index contract.
     */
    @Test
    fun `the journal chronology mirrors the full lifecycle scenario of all ten actions`() {
        val owner = messagingUser("owner")
        val (bob, carol) = messagingUser("bob") to messagingUser("carol")
        addContact(owner, bob.id)
        addContact(owner, carol.id)
        addContact(bob, carol.id) // the №31 re-add below runs under the NEW owner (№35)
        val chatId = chatIdOf(createGroupOk(owner, GROUP_TITLE, memberUserIds = listOf(bob.id, carol.id)))

        driveLifecycleScenario(owner, bob, carol, chatId)

        val chronology = adminLog.chronology(chatId)
        val facts = factsOf(chronology)
        // The №27 trio (group_created + member_added×2) lands in ONE
        // transaction, and PG stamps every row of a transaction with the
        // SAME now() — the chronology tiebreak is the random row id, so
        // the atomic trio is an UNORDERED group by design (FR-002:
        // the whole №27 commits together or not at all).
        assertThat(facts.take(CREATE_FACT_COUNT))
            .overridingErrorMessage(
                "the №27 atomic trio must journal group_created + one member_added per initial member, got <%s>",
                facts.take(CREATE_FACT_COUNT),
            ).containsExactlyInAnyOrderElementsOf(expectedCreateTrio(owner, bob, carol))
        // Every fact AFTER the create is one-per-transaction and the
        // transactions run strictly in sequence — the tail timeline is
        // exactly the operation ladder, oldest first.
        assertThat(facts.drop(CREATE_FACT_COUNT))
            .overridingErrorMessage(
                "the journal must read back as the EXACT scenario timeline (action, actor, target), got <%s>",
                facts,
            ).containsExactlyElementsOf(expectedPostCreateTimeline(owner, bob, carol))

        assertThat(chronology.map(GroupAdminLogEntry::action).toSet())
            .overridingErrorMessage("the scenario must exercise ALL TEN journal actions of data-model.md §Сущность 4")
            .containsExactlyInAnyOrderElementsOf(GroupAdminAction.entries)

        assertJournalStamps(chronology)
    }

    /**
     * The deterministic operation ladder of the chronology scenario: №34
     * grant + revoke → №29 title + description patches → №35 transfer →
     * №32 kick by the new owner → №31 re-add of the removed member → the
     * IDEMPOTENT №31 no-op (must journal NOTHING) → №33 leave → №30
     * delete. Every step asserts its contract status, so the fixture
     * never journals from a half-failed scenario.
     */
    private fun driveLifecycleScenario(
        owner: MessagingUser,
        bob: MessagingUser,
        carol: MessagingUser,
        chatId: UUID,
    ) {
        grantAdminOk(owner, chatId, bob.id)
        assertThat(setMemberRole(owner, chatId, bob.id, MEMBER_ROLE).statusCode)
            .overridingErrorMessage("fixture №34 revoke must answer 200")
            .isEqualTo(HttpStatus.OK)
        assertThat(patchGroup(owner, chatId, mapOf(TITLE_FIELD to RENAMED_TITLE)).statusCode)
            .overridingErrorMessage("fixture №29 title patch must answer 200")
            .isEqualTo(HttpStatus.OK)
        assertThat(patchGroup(owner, chatId, mapOf(DESCRIPTION_FIELD to RENAMED_DESCRIPTION)).statusCode)
            .overridingErrorMessage("fixture №29 description patch must answer 200")
            .isEqualTo(HttpStatus.OK)
        assertThat(transferOwnership(owner, chatId, bob.id).statusCode)
            .overridingErrorMessage("fixture №35 transfer must answer 200")
            .isEqualTo(HttpStatus.OK)
        assertThat(kickMember(bob, chatId, carol.id).statusCode)
            .overridingErrorMessage("fixture №32 kick by the new owner must answer 204")
            .isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(addGroupMembers(bob, chatId, listOf(carol.id)).statusCode)
            .overridingErrorMessage("fixture №31 re-add of the removed member must answer 200")
            .isEqualTo(HttpStatus.OK)
        assertThat(addGroupMembers(bob, chatId, listOf(carol.id)).statusCode)
            .overridingErrorMessage("the IDEMPOTENT №31 of an active member must still answer 200")
            .isEqualTo(HttpStatus.OK)
        val leave = leaveGroup(carol, chatId)
        assertThat(leave.statusCode)
            .overridingErrorMessage(
                "№33 leave of a plain member must answer 204, got <%s>: %s",
                leave.statusCode,
                leave.body,
            ).isEqualTo(HttpStatus.NO_CONTENT)
        val deletion = deleteGroup(bob, chatId)
        assertThat(deletion.statusCode)
            .overridingErrorMessage(
                "№30 delete by the owner must answer 204, got <%s>: %s",
                deletion.statusCode,
                deletion.body,
            ).isEqualTo(HttpStatus.NO_CONTENT)
    }

    /**
     * The `(action, actor, target)` facts of the ONE №27 transaction:
     * `group_created` plus one `member_added` per initial member — an
     * UNORDERED group (same transaction timestamp, random id tiebreak).
     */
    private fun expectedCreateTrio(
        owner: MessagingUser,
        bob: MessagingUser,
        carol: MessagingUser,
    ): List<Triple<GroupAdminAction, UUID, UUID?>> =
        listOf(
            Triple(GroupAdminAction.GROUP_CREATED, owner.id, null),
            Triple(GroupAdminAction.MEMBER_ADDED, owner.id, bob.id),
            Triple(GroupAdminAction.MEMBER_ADDED, owner.id, carol.id),
        )

    /** The one-fact-per-operation tail the scenario must leave behind, oldest first. */
    private fun expectedPostCreateTimeline(
        owner: MessagingUser,
        bob: MessagingUser,
        carol: MessagingUser,
    ): List<Triple<GroupAdminAction, UUID, UUID?>> =
        listOf(
            Triple(GroupAdminAction.ADMIN_GRANTED, owner.id, bob.id),
            Triple(GroupAdminAction.ADMIN_REVOKED, owner.id, bob.id),
            Triple(GroupAdminAction.TITLE_CHANGED, owner.id, null),
            Triple(GroupAdminAction.DESCRIPTION_CHANGED, owner.id, null),
            Triple(GroupAdminAction.OWNERSHIP_TRANSFERRED, owner.id, bob.id),
            Triple(GroupAdminAction.MEMBER_REMOVED, bob.id, carol.id),
            Triple(GroupAdminAction.MEMBER_ADDED, bob.id, carol.id),
            Triple(GroupAdminAction.MEMBER_LEFT, carol.id, carol.id),
            Triple(GroupAdminAction.GROUP_DELETED, bob.id, null),
        )

    /**
     * The chronology timestamps of the audit read: every row carries the
     * SERVER-stamped `now()` (never the EPOCH sentinel of a freshly
     * built entry) and the read is ordered by `created_at`, oldest
     * first — the `(group_id, created_at)` index contract of V14.
     */
    private fun assertJournalStamps(chronology: List<GroupAdminLogEntry>) {
        val stamps = chronology.map(GroupAdminLogEntry::createdAt)
        assertThat(stamps)
            .overridingErrorMessage("every journal row carries its SERVER-stamped timestamp, never the EPOCH sentinel")
            .allSatisfy { stamp -> assertThat(stamp).isAfter(Instant.EPOCH) }
        assertThat(stamps)
            .overridingErrorMessage("the chronology read must be ordered by created_at, oldest first")
            .isSorted()
    }

    /**
     * The FR-006 survival leg: the №30 hard delete erases the group WHOLE
     * (chats/messages/chat_participants — the SQL probe) yet the journal
     * of the gone group id still reads back WHOLE plus the final
     * `group_deleted` fact WITHOUT CONTENT — `target_user_id` NULL and
     * no payload columns to carry: the audit keeps the FACT of the
     * deletion, never the deleted content (FR-017, clarify 2026-09-25).
     * The raw table probe pins the survival on the STORAGE: the V14 table
     * deliberately has NO FK on `chats`.
     */
    @Test
    fun `the journal survives the hard delete of its group`() {
        val owner = messagingUser("owner")
        val bob = messagingUser("bob")
        addContact(owner, bob.id)
        val chatId = chatIdOf(createGroupOk(owner, GROUP_TITLE, memberUserIds = listOf(bob.id)))
        assertThat(patchGroup(owner, chatId, mapOf(TITLE_FIELD to RENAMED_TITLE)).statusCode)
            .overridingErrorMessage("fixture №29 title patch must answer 200")
            .isEqualTo(HttpStatus.OK)
        assertThat(adminLog.chronology(chatId).map(GroupAdminLogEntry::action))
            .overridingErrorMessage(
                "the fixture must journal the №27 pair (an UNORDERED same-transaction group) + the №29 title fact",
            ).containsExactlyInAnyOrder(
                GroupAdminAction.GROUP_CREATED,
                GroupAdminAction.MEMBER_ADDED,
                GroupAdminAction.TITLE_CHANGED,
            )

        val response = deleteGroup(owner, chatId)
        assertThat(response.statusCode)
            .overridingErrorMessage(
                "№30 hard-delete by the owner must answer 204, got <%s>: %s",
                response.statusCode,
                response.body,
            ).isEqualTo(HttpStatus.NO_CONTENT)

        assertThat(hardDeleteResidueOf(chatId))
            .overridingErrorMessage("№30 must erase chats/messages/chat_participants WHOLE (FR-006 CASCADE)")
            .isEqualTo(Triple(0L, 0L, 0L))

        val survivors = adminLog.chronology(chatId)
        assertThat(survivors.map(GroupAdminLogEntry::action))
            .overridingErrorMessage(
                "the journal must SURVIVE the hard delete WHOLE plus the final group_deleted (FR-006)",
            ).containsExactlyInAnyOrder(
                GroupAdminAction.GROUP_CREATED,
                GroupAdminAction.MEMBER_ADDED,
                GroupAdminAction.TITLE_CHANGED,
                GroupAdminAction.GROUP_DELETED,
            )
        assertThat(survivors.map(GroupAdminLogEntry::action).last())
            .overridingErrorMessage("the №30 fact rides its OWN later transaction — it is the LAST entry")
            .isEqualTo(GroupAdminAction.GROUP_DELETED)
        val deletion = survivors.last()
        assertThat(deletion.actorId)
            .overridingErrorMessage("the deleting owner is the actor of the group_deleted fact")
            .isEqualTo(owner.id)
        assertThat(deletion.targetUserId)
            .overridingErrorMessage("group_deleted is journaled WITHOUT CONTENT — the fact only (FR-006/FR-017)")
            .isNull()

        assertThat(journalRowCount(chatId))
            .overridingErrorMessage(
                "the raw group_admin_log rows must still sit on disk for the gone group (no FK, V14)",
            ).isEqualTo(JOURNAL_ROWS_AFTER_DELETE)
        assertThat(deletedFactTarget(chatId))
            .overridingErrorMessage("the stored group_deleted row carries target_user_id NULL — no content")
            .isNull()
    }

    /** The `(action, actor, target)` projection of a chronology read — the comparable timeline. */
    private fun factsOf(chronology: List<GroupAdminLogEntry>): List<Triple<GroupAdminAction, UUID, UUID?>> =
        chronology.map { Triple(it.action, it.actorId, it.targetUserId) }

    /** Contract №27 `POST /api/v1/groups` — happy path → the stored `GroupView` body. */
    private fun createGroupOk(
        owner: MessagingUser,
        title: String,
        memberUserIds: List<UUID> = emptyList(),
    ): JsonNode {
        val response =
            restTemplate.postForEntity(
                GROUPS_PATH,
                HttpEntity(
                    buildMap {
                        put(TITLE_FIELD, title)
                        if (memberUserIds.isNotEmpty()) {
                            put(MEMBER_USER_IDS_FIELD, memberUserIds.map(UUID::toString))
                        }
                    },
                    HttpHeaders().apply {
                        contentType = MediaType.APPLICATION_JSON
                        setBearerAuth(owner.accessToken)
                    },
                ),
                String::class.java,
            )
        assertThat(response.statusCode)
            .overridingErrorMessage(
                "fixture №27 create must answer 201, got <%s>: %s",
                response.statusCode,
                response.body,
            ).isEqualTo(HttpStatus.CREATED)
        return objectMapper.readTree(response.body)
    }

    /** Contract №31 `POST /api/v1/groups/{chatId}/members` — raw response. */
    private fun addGroupMembers(
        user: MessagingUser,
        chatId: UUID,
        userIds: List<UUID>,
    ): ResponseEntity<String> =
        restTemplate.postForEntity(
            "$GROUPS_PATH/$chatId/members",
            HttpEntity(
                mapOf(USER_IDS_FIELD to userIds.map(UUID::toString)),
                HttpHeaders().apply {
                    contentType = MediaType.APPLICATION_JSON
                    setBearerAuth(user.accessToken)
                },
            ),
            String::class.java,
        )

    /** Contract №34 `PUT /api/v1/groups/{chatId}/members/{userId}/role` — raw response. */
    private fun setMemberRole(
        user: MessagingUser,
        chatId: UUID,
        userId: UUID,
        role: String,
    ): ResponseEntity<String> =
        restTemplate.exchange(
            "$GROUPS_PATH/$chatId/members/$userId/role",
            HttpMethod.PUT,
            HttpEntity(
                mapOf(ROLE_FIELD to role),
                HttpHeaders().apply {
                    contentType = MediaType.APPLICATION_JSON
                    setBearerAuth(user.accessToken)
                },
            ),
            String::class.java,
        )

    /** Contract №35 `POST /api/v1/groups/{chatId}/owner` — raw response. */
    private fun transferOwnership(
        user: MessagingUser,
        chatId: UUID,
        userId: UUID,
    ): ResponseEntity<String> =
        restTemplate.postForEntity(
            "$GROUPS_PATH/$chatId/owner",
            HttpEntity(
                mapOf(USER_ID_FIELD to userId.toString()),
                HttpHeaders().apply {
                    contentType = MediaType.APPLICATION_JSON
                    setBearerAuth(user.accessToken)
                },
            ),
            String::class.java,
        )

    /** Contract №32 `DELETE /api/v1/groups/{chatId}/members/{userId}` — raw response. */
    private fun kickMember(
        user: MessagingUser,
        chatId: UUID,
        userId: UUID,
    ): ResponseEntity<String> =
        restTemplate.exchange(
            "$GROUPS_PATH/$chatId/members/$userId",
            HttpMethod.DELETE,
            HttpEntity<Unit>(Unit, HttpHeaders().apply { setBearerAuth(user.accessToken) }),
            String::class.java,
        )

    /** Contract №33 `DELETE /api/v1/groups/{chatId}/membership` — raw response (US6, T063/T065). */
    private fun leaveGroup(
        user: MessagingUser,
        chatId: UUID,
    ): ResponseEntity<String> =
        restTemplate.exchange(
            "$GROUPS_PATH/$chatId/membership",
            HttpMethod.DELETE,
            HttpEntity<Unit>(Unit, HttpHeaders().apply { setBearerAuth(user.accessToken) }),
            String::class.java,
        )

    /** Contract №30 `DELETE /api/v1/groups/{chatId}` — raw response (US6, T064/T065). */
    private fun deleteGroup(
        user: MessagingUser,
        chatId: UUID,
    ): ResponseEntity<String> =
        restTemplate.exchange(
            "$GROUPS_PATH/$chatId",
            HttpMethod.DELETE,
            HttpEntity<Unit>(Unit, HttpHeaders().apply { setBearerAuth(user.accessToken) }),
            String::class.java,
        )

    /**
     * Contract №29 `PATCH /api/v1/groups/{chatId}` — raw response. The
     * call rides the JDK HttpClient on purpose: the build carries no
     * Apache HTTP client (plan.md VII) and RestTemplate's default JDK
     * factory refuses the PATCH method — the same deviation the №29 leg
     * of GroupLifecycleIT records. The response is rebuilt as a
     * `ResponseEntity` (status + body), so the shared assertion helpers
     * keep working verbatim.
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
        return ResponseEntity.status(response.statusCode()).body(response.body())
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
        val response =
            restTemplate.postForEntity(
                CONTACTS_PATH,
                HttpEntity(
                    mapOf(USER_ID_FIELD to userId.toString()),
                    HttpHeaders().apply {
                        contentType = MediaType.APPLICATION_JSON
                        setBearerAuth(user.accessToken)
                    },
                ),
                String::class.java,
            )
        assertThat(response.statusCode.is2xxSuccessful)
            .overridingErrorMessage(
                "fixture contact №21 of <%s> → <%s> must succeed, got <%s>: %s",
                user.username,
                userId,
                response.statusCode,
                response.body,
            ).isTrue
    }

    private fun chatIdOf(view: JsonNode): UUID = UUID.fromString(view["chatId"].asText())

    /**
     * The №30 hard-delete residue as `(chats, messages, chat_participants)`
     * row counts — the FR-006 SQL probe: a whole-erased group leaves ZERO
     * rows in every one of the three tables (CASCADE by the V10 FKs).
     */
    private fun hardDeleteResidueOf(chatId: UUID): Triple<Long, Long, Long> =
        jdbcTemplate.queryForObject(
            """
            SELECT (SELECT count(*) FROM chats WHERE id = ?) AS chats_left,
                   (SELECT count(*) FROM messages WHERE chat_id = ?) AS messages_left,
                   (SELECT count(*) FROM chat_participants WHERE chat_id = ?) AS participants_left
            """.trimIndent(),
            { rs, _ -> Triple(rs.getLong("chats_left"), rs.getLong("messages_left"), rs.getLong("participants_left")) },
            chatId,
            chatId,
            chatId,
        ) ?: Triple(-1L, -1L, -1L)

    /** The journal rows still on disk for [chatId] — the raw FR-006 survival probe (no FK, V14). */
    private fun journalRowCount(chatId: UUID): Long =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM group_admin_log WHERE group_id = ?",
            Long::class.java,
            chatId,
        ) ?: 0L

    /** The stored `target_user_id` of the `group_deleted` fact — NULL: the fact only, no content. */
    private fun deletedFactTarget(chatId: UUID): UUID? =
        jdbcTemplate
            .query(
                "SELECT target_user_id FROM group_admin_log WHERE group_id = ? AND action = 'group_deleted'",
                { rs, _ -> rs.getObject("target_user_id", UUID::class.java) },
                chatId,
            ).singleOrNull()

    private companion object {
        const val GROUPS_PATH = "/api/v1/groups"
        const val CONTACTS_PATH = "/api/v1/contacts"

        const val TITLE_FIELD = "title"
        const val DESCRIPTION_FIELD = "description"
        const val MEMBER_USER_IDS_FIELD = "memberUserIds"
        const val USER_IDS_FIELD = "userIds"
        const val USER_ID_FIELD = "userId"
        const val ROLE_FIELD = "role"

        const val ADMIN_ROLE = "admin"
        const val MEMBER_ROLE = "member"

        /** US6 journal fixtures. */
        const val GROUP_TITLE = "Admin Log IT"
        const val RENAMED_TITLE = "Admin Log IT renamed"
        const val RENAMED_DESCRIPTION = "the US6 audit fixture description"

        /** The survival fixture journals 3 facts before the №30 plus the deletion itself. */
        const val JOURNAL_ROWS_AFTER_DELETE = 4L

        /** The №27 atomic trio: group_created + one member_added per initial member. */
        const val CREATE_FACT_COUNT = 3

        /** The №29 JDK-HttpClient leg: RestTemplate's default JDK factory refuses PATCH. */
        const val PATCH_METHOD = "PATCH"
        const val PATCH_CONNECT_TIMEOUT_SECONDS = 5L
    }
}
