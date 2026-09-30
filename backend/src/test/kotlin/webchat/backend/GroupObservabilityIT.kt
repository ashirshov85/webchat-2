package webchat.backend

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import webchat.backend.sync.SyncTestSupport
import java.time.Duration
import java.util.UUID

/**
 * T068 (tasks.md Phase 9, FR-017/SC-008, quickstart §3.8) — the
 * observability validation of the 006 group feature: the FOUR FR-017
 * meter families of [webchat.backend.groups.GroupMetrics] (T010) must
 * answer on `/actuator/prometheus` and grow MONOTONICALLY in the
 * quickstart scenarios they were built to observe, and the group's
 * `group_admin_log` chronology must mirror the executed scenario:
 *
 *  * §3.1 (create + №31 add) and §3.2 (group exchange): every №16 send
 *    fans `message.created` out to the ACTIVE roster minus the sender,
 *    so `webchat_group_message_fanout_total` grows by the LEG COUNT of
 *    each send and `webchat_group_message_fanout_seconds` gains one
 *    sample per send (SC-001 signal), while every membership transition
 *    (№27 create, №31 add) records `webchat_group_size` (research.md
 *    §8: the size distribution follows the roster);
 *  * §3.5 (privacy): every refusal of the membership gate — a stranger
 *    driving №28/№31/№32/№33/№35 — grows
 *    `webchat_group_authz_denials_total` BY ITS `operation` TAG (one
 *    counted 404 per refusal, SC-003);
 *  * §3.8 step 1: the journal of the scenario group reads back as the
 *    executed timeline — `group_created` + one `member_added` per №27
 *    roster entry (one №27 transaction), then the №31 `member_added` of
 *    the later addition, all actor=owner. The FULL ten-action chronology
 *    is owned by GroupAdminLogIT (T060); THIS class pins the §3.8
 *    operator's cut of the same storage.
 *
 * The meters are read the way an operator reads them — by scraping the
 * real `/actuator/prometheus` text exposition (never through the
 * MeterRegistry), so the verification covers the whole export path:
 * registration, naming, the `operation`/`application` tags and the
 * endpoint exposure itself. Deltas between awaited snapshots (not
 * absolute values) make the IT immune to the shared Spring test context
 * carrying counters from earlier IT classes, and every leg drives the
 * REAL 001–006 flows over Testcontainers PG+Redis — no mocks.
 */
@AutoConfigureObservability
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@Suppress("TooManyFunctions") // T068: one helper per scrape/flow/journal concern, mirroring ObservabilityIT
class GroupObservabilityIT(
    @Autowired private val apiRestTemplate: TestRestTemplate,
    @Autowired private val observabilityObjectMapper: ObjectMapper,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) : SyncTestSupport() {
    /**
     * Quickstart §3.1/§3.2: the lifecycle-messaging scenario of one
     * group — №27 create with one contact, a №16 send, the №31
     * re-enlargement to three actives and the second №16 send over the
     * WIDER roster. The two awaited snapshots around the scenario halves
     * prove the MONOTONE accumulation the acceptance of T068 demands:
     * `mid ≥ before` (awaited) and `after ≥ mid` (awaited) for every one
     * of the three delivery-side meters, with the exact scenario
     * minimums (legs 1 + 2, one timer sample per send, one size sample
     * per membership transition).
     */
    @Test
    @Order(1)
    fun `quickstart 3_1 and 3_2 scenarios grow fanout and group size meters monotonically`() {
        val owner = messagingUser("owner")
        val (bob, carol) = messagingUser("bob") to messagingUser("carol")
        addContact(owner, bob.id)
        addContact(owner, carol.id)

        val fanoutBefore = current(FANOUT_TOTAL)
        val fanoutSecondsBefore = current(FANOUT_SECONDS_COUNT)
        val groupSizeBefore = current(GROUP_SIZE_COUNT)

        val chatId = chatIdOf(createGroupOk(owner, listOf(bob.id)))
        assertThat(sendMessage(owner, chatId, FIRST_SEND_TEXT).statusCode)
            .overridingErrorMessage("the first §3.2 group send must answer 201")
            .isEqualTo(HttpStatus.CREATED)

        // Half one lands: one fanout leg (roster minus sender = bob),
        // one timer sample, one size sample (the №27 roster of two).
        assertGrows(fanoutBefore, MINIMUM_ONE, FANOUT_TOTAL)
        assertGrows(fanoutSecondsBefore, MINIMUM_ONE, FANOUT_SECONDS_COUNT)
        assertGrows(groupSizeBefore, MINIMUM_ONE, GROUP_SIZE_COUNT)

        val fanoutMid = current(FANOUT_TOTAL)
        val fanoutSecondsMid = current(FANOUT_SECONDS_COUNT)
        val groupSizeMid = current(GROUP_SIZE_COUNT)

        assertThat(addGroupMembers(owner, chatId, listOf(carol.id)).statusCode)
            .overridingErrorMessage("the §3.1 №31 addition must answer 200")
            .isEqualTo(HttpStatus.OK)
        assertThat(sendMessage(bob, chatId, SECOND_SEND_TEXT).statusCode)
            .overridingErrorMessage("the second §3.2 group send must answer 201")
            .isEqualTo(HttpStatus.CREATED)

        // Half two lands over the WIDER roster: TWO more legs (owner +
        // carol), a second timer sample and a second size sample — and
        // the whole-scenario minimums hold against the FIRST snapshot.
        assertGrows(fanoutMid, MINIMUM_TWO, FANOUT_TOTAL)
        assertGrows(fanoutSecondsMid, MINIMUM_ONE, FANOUT_SECONDS_COUNT)
        assertGrows(groupSizeMid, MINIMUM_ONE, GROUP_SIZE_COUNT)
        assertGrows(fanoutBefore, MINIMUM_THREE, FANOUT_TOTAL)
        assertGrows(fanoutSecondsBefore, MINIMUM_TWO, FANOUT_SECONDS_COUNT)
        assertGrows(groupSizeBefore, MINIMUM_TWO, GROUP_SIZE_COUNT)
    }

    /**
     * Quickstart §3.5: a stranger driving FIVE gated group operations —
     * №28 get, №31 add_members, №32 remove_member, №33 leave and №35
     * transfer_ownership — every refusal is one counted 404, and the
     * counter answers BY ITS `operation` TAG: each series grows by at
     * least its own refusal, the `get` series by TWO (the repeated
     * refusal of the monotonicity probe). The 004 paths (№15–№17/№25)
     * never grow this counter (the operation vocabulary is №28–№35
     * only, research.md §8) — owned by GroupPrivacyIT (T054).
     */
    @Test
    @Order(2)
    fun `quickstart 3_5 refusals grow the authz denials counter by operation`() {
        val owner = messagingUser("owner")
        val (bob, carol) = messagingUser("bob") to messagingUser("carol")
        addContact(owner, bob.id)
        val chatId = chatIdOf(createGroupOk(owner, listOf(bob.id)))

        val getBefore = current(AUTHZ_DENIALS_TOTAL, TAG_OPERATION_GET)
        val addMembersBefore = current(AUTHZ_DENIALS_TOTAL, TAG_OPERATION_ADD_MEMBERS)
        val removeMemberBefore = current(AUTHZ_DENIALS_TOTAL, TAG_OPERATION_REMOVE_MEMBER)
        val leaveBefore = current(AUTHZ_DENIALS_TOTAL, TAG_OPERATION_LEAVE)
        val transferBefore = current(AUTHZ_DENIALS_TOTAL, TAG_OPERATION_TRANSFER_OWNERSHIP)

        assertUniform404(getGroup(carol, chatId))
        assertUniform404(addGroupMembers(carol, chatId, listOf(bob.id)))
        assertUniform404(kickMember(carol, chatId, bob.id))
        assertUniform404(leaveGroup(carol, chatId))
        assertUniform404(transferOwnership(carol, chatId, bob.id))

        assertGrows(getBefore, MINIMUM_ONE, AUTHZ_DENIALS_TOTAL, TAG_OPERATION_GET)
        assertGrows(addMembersBefore, MINIMUM_ONE, AUTHZ_DENIALS_TOTAL, TAG_OPERATION_ADD_MEMBERS)
        assertGrows(removeMemberBefore, MINIMUM_ONE, AUTHZ_DENIALS_TOTAL, TAG_OPERATION_REMOVE_MEMBER)
        assertGrows(leaveBefore, MINIMUM_ONE, AUTHZ_DENIALS_TOTAL, TAG_OPERATION_LEAVE)
        assertGrows(transferBefore, MINIMUM_ONE, AUTHZ_DENIALS_TOTAL, TAG_OPERATION_TRANSFER_OWNERSHIP)

        // The monotonicity probe: a SECOND refusal of the same operation
        // grows the SAME series further — counters never reset.
        assertUniform404(getGroup(carol, chatId))
        assertGrows(getBefore, MINIMUM_TWO, AUTHZ_DENIALS_TOTAL, TAG_OPERATION_GET)
    }

    /**
     * Quickstart §3.8 step 1 — the §3.8 operator's cut of the journal:
     * after a №27 create with one contact and a №31 addition, the
     * `group_admin_log` chronology of THAT group reads `group_created` +
     * the №27 `member_added` of the initial roster (one transaction,
     * same `now()` — an UNORDERED pair by design), then the №31
     * `member_added` of the later addition, every fact actor=owner. The
     * full ten-action timeline is T060 (GroupAdminLogIT).
     */
    @Test
    @Order(3)
    fun `the group admin log chronology mirrors the observability scenario`() {
        val owner = messagingUser("owner")
        val (bob, carol) = messagingUser("bob") to messagingUser("carol")
        addContact(owner, bob.id)
        addContact(owner, carol.id)
        val chatId = chatIdOf(createGroupOk(owner, listOf(bob.id)))
        assertThat(addGroupMembers(owner, chatId, listOf(carol.id)).statusCode)
            .overridingErrorMessage("the №31 addition must answer 200")
            .isEqualTo(HttpStatus.OK)

        val facts = adminLogFacts(chatId)
        assertThat(facts.map(AdminLogFact::action).take(CREATE_TRANSACTION_FACTS))
            .overridingErrorMessage(
                "the №27 transaction must journal group_created + one member_added, got <%s>",
                facts.map(AdminLogFact::action),
            ).containsExactlyInAnyOrderElementsOf(listOf(ACTION_GROUP_CREATED, ACTION_MEMBER_ADDED))
        val tailActions = facts.drop(CREATE_TRANSACTION_FACTS).map(AdminLogFact::action)
        assertThat(tailActions)
            .overridingErrorMessage("the №31 addition must journal exactly one member_added after №27, got <%s>", facts)
            .containsExactly(ACTION_MEMBER_ADDED)
        assertThat(facts.map(AdminLogFact::actor).toSet())
            .overridingErrorMessage("every fact of the scenario must name the owner as actor, got <%s>", facts)
            .containsExactly(owner.id)
    }

    /**
     * The exposure head of the acceptance: after the scenarios above,
     * every one of the FOUR FR-017 meter families is present in the real
     * exposition with its HELP header AND a live sample series — the
     * exact surface an operator scrapes at §3.8 step 2.
     */
    @Test
    @Order(4)
    fun `all four FR-017 meter families are exposed on actuator prometheus`() {
        val exposition = scrape()

        FR017_METERS.forEach { meter ->
            assertThat(exposition)
                .overridingErrorMessage(
                    "FR-017 requires <%s> on /actuator/prometheus, got:%n%s",
                    meter,
                    excerpt(exposition, meter),
                ).contains("# HELP $meter")
        }
        SAMPLE_SERIES.forEach { sample ->
            assertThat(exposition.lineSequence().any { it.startsWith("$sample{") || it.startsWith("$sample ") })
                .overridingErrorMessage(
                    "FR-017 requires the sample series <%s> on /actuator/prometheus, got:%n%s",
                    sample,
                    excerpt(exposition, sample),
                ).isTrue
        }
    }

    // --- scrape helpers: the operator's view of the meters, not the registry's ---

    /** GET /actuator/prometheus — the real text exposition. */
    private fun scrape(): String {
        val response = apiRestTemplate.getForEntity(PROMETHEUS_PATH, String::class.java)
        assertThat(response.statusCode)
            .overridingErrorMessage("the prometheus exposition must answer 200, got <%s>", response.statusCode)
            .isEqualTo(HttpStatus.OK)
        return response.body.orEmpty()
    }

    /**
     * One sample value of [sampleName] whose label set contains every
     * [tags] entry (e.g. `operation="get"`); `0.0` when the meter has no
     * sample yet — the lawful «before» value of a delta.
     */
    private fun current(
        sampleName: String,
        vararg tags: String,
    ): Double {
        val wanted = tags.joinToString(separator = "")
        val sample =
            scrape()
                .lineSequence()
                .mapNotNull { line -> SAMPLE_PATTERN.matchEntire(line.trim()) }
                .firstOrNull { match ->
                    match.groupValues[SAMPLE_NAME_GROUP] == sampleName &&
                        (wanted.isEmpty() || match.groupValues[SAMPLE_LABELS_GROUP].contains(wanted))
                }
        return sample?.let { match -> match.groupValues[SAMPLE_VALUE_GROUP].toDouble() } ?: 0.0
    }

    /** Awaits the scenario minimum growth of a sample (deltas, not absolutes). */
    private fun assertGrows(
        before: Double,
        byAtLeast: Double,
        sampleName: String,
        vararg tags: String,
    ) {
        await().atMost(METER_WAIT).untilAsserted {
            val now = current(sampleName, *tags)
            assertThat(now - before)
                .overridingErrorMessage(
                    "SC-008: <%s{%s}> must grow by at least <%s> after the scenario " +
                        "(before=<%s>, now=<%s>)",
                    sampleName,
                    tags.joinToString(separator = ","),
                    byAtLeast,
                    before,
                    now,
                ).isGreaterThanOrEqualTo(byAtLeast)
        }
    }

    // --- flow helpers over the same real HTTP (№21/№27/№28/№31/№32/№33/№35/№16) ---

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

    /** Contract №27 `POST /api/v1/groups` — the 201 `GroupView` body of the happy path. */
    private fun createGroupOk(
        owner: MessagingUser,
        memberUserIds: List<UUID>,
    ): JsonNode {
        val payload =
            mapOf(
                TITLE_FIELD to GROUP_TITLE,
                MEMBER_USER_IDS_FIELD to memberUserIds.map(UUID::toString),
            )
        val response = postJsonAuthorized(GROUPS_PATH, payload, owner)
        assertThat(response.statusCode)
            .overridingErrorMessage("№27 create must answer 201, got <%s>: %s", response.statusCode, response.body)
            .isEqualTo(HttpStatus.CREATED)
        return observabilityObjectMapper.readTree(response.body)
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

    /** Contract №33 `DELETE /api/v1/groups/{chatId}/membership` — raw response. */
    private fun leaveGroup(
        user: MessagingUser,
        chatId: UUID,
    ): ResponseEntity<String> = exchangeWithAuth(HttpMethod.DELETE, "$GROUPS_PATH/$chatId/membership", user)

    private fun postJsonAuthorized(
        path: String,
        payload: Map<String, Any>,
        user: MessagingUser,
    ): ResponseEntity<String> =
        apiRestTemplate.postForEntity(
            path,
            HttpEntity(
                payload,
                HttpHeaders().apply {
                    contentType = MediaType.APPLICATION_JSON
                    setBearerAuth(user.accessToken)
                },
            ),
            String::class.java,
        )

    private fun exchangeWithAuth(
        method: HttpMethod,
        path: String,
        user: MessagingUser,
    ): ResponseEntity<String> =
        apiRestTemplate.exchange(
            path,
            method,
            HttpEntity<Unit>(Unit, HttpHeaders().apply { setBearerAuth(user.accessToken) }),
            String::class.java,
        )

    private fun chatIdOf(view: JsonNode): UUID = UUID.fromString(view["chatId"].asText())

    // --- journal helpers: the §3.8 chronology probe ---

    /** One `(action, actor)` fact of the group's append-only journal, oldest first. */
    private data class AdminLogFact(
        val action: String,
        val actor: UUID,
    )

    /** The group's `group_admin_log` chronology — oldest first by `(created_at, id)`. */
    private fun adminLogFacts(chatId: UUID): List<AdminLogFact> =
        jdbcTemplate.query(
            """
            SELECT action, actor_id FROM group_admin_log
            WHERE group_id = ?
            ORDER BY created_at, id
            """.trimIndent(),
            { rs, _ -> AdminLogFact(rs.getString("action"), UUID.fromString(rs.getString("actor_id"))) },
            chatId,
        )

    /** The uniform §3.5 refusal: `404` problem `errors: {group: [group_not_found]}` (FR-008). */
    private fun assertUniform404(response: ResponseEntity<String>) {
        assertThat(response.statusCode)
            .overridingErrorMessage(
                "the §3.5 refusal must answer the uniform 404, got <%s>: %s",
                response.statusCode,
                response.body,
            ).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(response.body.orEmpty())
            .overridingErrorMessage(
                "the §3.5 refusal must carry the contract code group_not_found, got <%s>",
                response.body,
            ).contains(GROUP_NOT_FOUND)
    }

    private fun excerpt(
        exposition: String,
        meter: String,
    ): String =
        exposition
            .lineSequence()
            .filter { line -> meter in line }
            .joinToString(separator = "\n")

    private companion object {
        /** research.md 006 §8 / quickstart §3.8: the FR-017 meter names of T010. */
        const val AUTHZ_DENIALS_TOTAL = "webchat_group_authz_denials_total"
        const val FANOUT_TOTAL = "webchat_group_message_fanout_total"
        const val FANOUT_SECONDS = "webchat_group_message_fanout_seconds"
        const val GROUP_SIZE = "webchat_group_size"

        /** The exposition sample series of the timer and the distribution summary (`_count` legs). */
        const val FANOUT_SECONDS_COUNT = "webchat_group_message_fanout_seconds_count"
        const val GROUP_SIZE_COUNT = "webchat_group_size_count"

        val FR017_METERS = listOf(AUTHZ_DENIALS_TOTAL, FANOUT_TOTAL, FANOUT_SECONDS, GROUP_SIZE)
        val SAMPLE_SERIES = listOf(FANOUT_SECONDS_COUNT, GROUP_SIZE_COUNT)

        /** Label selectors as they appear inside the exposition braces (№28–№35 operation ids). */
        const val TAG_OPERATION_GET = "operation=\"get\""
        const val TAG_OPERATION_ADD_MEMBERS = "operation=\"add_members\""
        const val TAG_OPERATION_REMOVE_MEMBER = "operation=\"remove_member\""
        const val TAG_OPERATION_LEAVE = "operation=\"leave\""
        const val TAG_OPERATION_TRANSFER_OWNERSHIP = "operation=\"transfer_ownership\""

        /** data-model.md §Сущность 4 — the №27/№31 journal actions of the scenario. */
        const val ACTION_GROUP_CREATED = "group_created"
        const val ACTION_MEMBER_ADDED = "member_added"
        const val CREATE_TRANSACTION_FACTS = 2

        const val GROUP_NOT_FOUND = "group_not_found"

        const val GROUPS_PATH = "/api/v1/groups"
        const val CONTACTS_PATH = "/api/v1/contacts"
        const val PROMETHEUS_PATH = "/actuator/prometheus"

        const val USER_ID_FIELD = "userId"
        const val USER_IDS_FIELD = "userIds"
        const val MEMBER_USER_IDS_FIELD = "memberUserIds"
        const val TITLE_FIELD = "title"

        const val GROUP_TITLE = "WebChat Observability IT"

        const val FIRST_SEND_TEXT = "the first observability scenario send"
        const val SECOND_SEND_TEXT = "the second observability scenario send over the wider roster"

        const val MINIMUM_ONE = 1.0
        const val MINIMUM_TWO = 2.0
        const val MINIMUM_THREE = 3.0

        val METER_WAIT: Duration = Duration.ofSeconds(10)

        /**
         * One exposition sample line: `name{labels} value` (the labels
         * braces may be absent for untagged series; the 006 meters all
         * carry at least the `application` common tag).
         */
        val SAMPLE_PATTERN = Regex("""([a-zA-Z_:][a-zA-Z0-9_:]*)(?:\{([^}]*)\})?\s+([0-9.eE+-]+)""")
        const val SAMPLE_NAME_GROUP = 1
        const val SAMPLE_LABELS_GROUP = 2
        const val SAMPLE_VALUE_GROUP = 3
    }
}
