package webchat.backend.users

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
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
import org.springframework.web.util.UriComponentsBuilder
import org.yaml.snakeyaml.Yaml
import webchat.backend.chats.MessagingTestSupport
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import java.util.UUID

/**
 * T009 (tasks.md Phase 3, US1): the profile-name slice of the 008a
 * contract (api-contract.md §1 №39 + §2, openapi.yaml 0.9.0,
 * FR-001/FR-005, US1 AC1/AC4/AC5):
 *
 *  * №39 `PUT /users/me/profile` — set/clear of the profile
 *    displayName: the SERVER trims surrounding whitespace before
 *    storing (the stored value and every later projection carry the
 *    trimmed form), `null`/an omitted field resets the name («not
 *    set»), and a repeat of the same value stays a plain 200
 *    (idempotent, no side events — there is no name-change realtime
 *    event by contract);
 *  * validation: an empty string, a whitespace-only string and a name
 *    longer than 64 characters AFTER trim are refused
 *    `400 invalid_display_name` (`errors: {displayName: [...]}`,
 *    problem+json) while the previously stored value SURVIVES (US1
 *    AC5: a refused save keeps the old name); the 64-character bound
 *    itself is valid; Unicode is unrestricted (cyrillic/emoji);
 *  * propagation: the shared `PublicUser` projector spreads the name
 *    over every `$ref` surface — №10 `GET /users/me`, №19 search
 *    results, the №11/№12/№13 peer fragments of the direct chat and
 *    the peer of the №26 sync delta, plus the №28 group member
 *    fragment (`UserWithAlias`); a user WITHOUT a name keeps the field
 *    ABSENT on all of those surfaces (backward compatibility —
 *    clients 008 render `username`, SC-007);
 *  * flood: the per-user bucket `rl:user:profile:{userId}` (30/min,
 *    №38 parity) refuses the burst `429 flood_limit` +
 *    integral `Retry-After ≥ 1` and is strictly per-user — a different
 *    user updates right after the refusal undisturbed.
 *
 * NOTE (TDD, constitution VI): written BEFORE T011/T012 (and the
 * projection legs of T015–T017) land — until the №39 route exists
 * every PUT here answers 404/405 and the projection legs never see a
 * displayName, so every method fails (RED) by design; T026 acceptance
 * is this class green together with ContactAliasIT.
 */
@Suppress("TooManyFunctions", "LargeClass") // T009: one helper per contract operation, one method per US1 rule
class ProfileDisplayNameIT(
    @Autowired private val restTemplate: TestRestTemplate,
    @Autowired private val objectMapper: ObjectMapper,
) : MessagingTestSupport() {
    /** №39 happy path (set leg): 200 + the trimmed `PublicUser` body; repeat stays 200. */
    @Test
    fun `update sets trims and repeats the display name idempotently`() {
        val alice = messagingUser("maria")

        // Backward-compat baseline: a fresh user carries NO displayName (US1 AC4).
        val baseline = getMe(alice)
        assertThat(baseline.statusCode)
            .overridingErrorMessage("№10 must answer 200, got <%s>: %s", baseline.statusCode, baseline.body)
            .isEqualTo(HttpStatus.OK)
        assertThat(bodyTree(baseline).hasDisplayName())
            .overridingErrorMessage("a user without a profile name must NOT carry displayName in №10")
            .isFalse
        assertConformsToSchema(objectMapper.readTree(baseline.body), PUBLIC_USER_SCHEMA)

        // Set with surrounding whitespace: the server trims BEFORE storing (FR-001).
        val setName = "  $PROFILE_NAME  "
        val set = updateProfile(alice, setName)
        assertThat(set.statusCode)
            .overridingErrorMessage(
                "№39 must accept a padded name and answer 200, got <%s>: %s",
                set.statusCode,
                set.body,
            ).isEqualTo(HttpStatus.OK)
        val setView = objectMapper.readTree(set.body!!)
        assertThat(setView.displayNameText())
            .overridingErrorMessage(
                "№39 must return the TRIMMED stored value, got <%s> in %s",
                setView.displayNameText(),
                set.body,
            ).isEqualTo(PROFILE_NAME)
        assertConformsToSchema(setView, PUBLIC_USER_SCHEMA)

        assertThat(bodyTree(getMe(alice)).displayNameText())
            .overridingErrorMessage("№10 must serve the stored displayName")
            .isEqualTo(PROFILE_NAME)

        // Idempotency: the same value again is a plain 200 with no side events.
        val repeat = updateProfile(alice, PROFILE_NAME)
        assertThat(repeat.statusCode)
            .overridingErrorMessage("a repeated №39 of the same value must stay 200, got <%s>", repeat.statusCode)
            .isEqualTo(HttpStatus.OK)

        // Unicode is unrestricted (US1 edge: cyrillic and emoji names).
        val unicode = updateProfile(alice, UNICODE_NAME)
        assertThat(unicode.statusCode)
            .overridingErrorMessage("№39 must accept a Unicode name, got <%s>: %s", unicode.statusCode, unicode.body)
            .isEqualTo(HttpStatus.OK)
        assertThat(objectMapper.readTree(unicode.body!!).displayNameText()).isEqualTo(UNICODE_NAME)
    }

    /** №39 clear leg: explicit null AND the omitted field reset the name («not set»). */
    @Test
    fun `null and the omitted field reset the display name`() {
        val alice = messagingUser("resetter")
        assertThat(updateProfile(alice, PROFILE_NAME).statusCode)
            .overridingErrorMessage("the fixture name must be storable before the reset legs")
            .isEqualTo(HttpStatus.OK)

        // Clear: explicit null resets to «not set» (NOT a blank string — that is a 400).
        val cleared = updateProfile(alice, null)
        assertThat(cleared.statusCode)
            .overridingErrorMessage(
                "№39 null must clear the name with 200, got <%s>: %s",
                cleared.statusCode,
                cleared.body,
            ).isEqualTo(HttpStatus.OK)
        assertThat(bodyTree(cleared).hasDisplayName())
            .overridingErrorMessage("a cleared profile must NOT carry displayName in the №39 answer")
            .isFalse
        assertThat(bodyTree(getMe(alice)).hasDisplayName())
            .overridingErrorMessage("a cleared profile must NOT carry displayName in №10")
            .isFalse

        // The omitted field carries the same reset semantics as null.
        val omitted = updateProfileOmittingField(alice)
        assertThat(omitted.statusCode)
            .overridingErrorMessage(
                "№39 without the field must reset with 200, got <%s>: %s",
                omitted.statusCode,
                omitted.body,
            ).isEqualTo(HttpStatus.OK)
        assertThat(bodyTree(omitted).hasDisplayName()).isFalse
    }

    /** №39 validation: blank/oversized names are 400 `invalid_display_name`, the stored value survives (US1 AC5). */
    @Test
    fun `blank and oversized names are refused 400 invalid_display_name keeping the stored value`() {
        val alice = messagingUser("validator")
        assertThat(updateProfile(alice, PROFILE_NAME).statusCode)
            .overridingErrorMessage("the fixture name must be storable before the refusals")
            .isEqualTo(HttpStatus.OK)

        assertInvalidDisplayName(updateProfile(alice, ""), "an empty string")
        assertInvalidDisplayName(updateProfile(alice, BLANK_NAME), "a whitespace-only string")
        assertInvalidDisplayName(updateProfile(alice, OVERSIZED_NAME), "a name longer than 64 characters")

        assertThat(bodyTree(getMe(alice)).displayNameText())
            .overridingErrorMessage("a refused №39 must keep the previously stored name (US1 AC5)")
            .isEqualTo(PROFILE_NAME)

        val boundary = updateProfile(alice, BOUNDARY_NAME)
        assertThat(boundary.statusCode)
            .overridingErrorMessage(
                "the 64-character bound itself must stay valid, got <%s>: %s",
                boundary.statusCode,
                boundary.body,
            ).isEqualTo(HttpStatus.OK)
        assertThat(objectMapper.readTree(boundary.body!!).displayNameText()).isEqualTo(BOUNDARY_NAME)
    }

    /**
     * FR-001/FR-005 propagation over the direct-chat surfaces: the peer
     * fragments of №11 (ensure), №12 (list) and №13 (get) plus the peer
     * of the №26 sync delta all reuse the shared `PublicUser` projector
     * — absent before the name is set (backward compatibility), the
     * stored name after. The №26 leg needs an undelivered message, so
     * the delta is created AFTER the name lands (the peer is rendered
     * at read time — a later rename shows up on the next fetch).
     */
    @Test
    fun `display name rides the №11 №12 №13 peer projections and the №26 sync delta peer`() {
        val (alice, bob) = messagingPair()

        val beforeEnsure = ensureChat(bob, alice.id)
        assertThat(beforeEnsure.statusCode.is2xxSuccessful)
            .overridingErrorMessage("№11 ensure must succeed, got <%s>: %s", beforeEnsure.statusCode, beforeEnsure.body)
            .isTrue
        val chatId = UUID.fromString(objectMapper.readTree(beforeEnsure.body!!)["chatId"].asText())

        assertThat(peerOf(objectMapper.readTree(beforeEnsure.body!!)).hasDisplayName())
            .overridingErrorMessage("№11 peer must NOT carry displayName while the peer has no name (SC-007)")
            .isFalse
        assertThat(peerOf(chatListItemOf(listChats(bob), chatId)).hasDisplayName())
            .overridingErrorMessage("№12 peer must NOT carry displayName while the peer has no name (SC-007)")
            .isFalse
        assertThat(peerOf(objectMapper.readTree(getChat(bob, chatId).body!!)).hasDisplayName())
            .overridingErrorMessage("№13 peer must NOT carry displayName while the peer has no name (SC-007)")
            .isFalse

        assertThat(updateProfile(alice, PROFILE_NAME).statusCode)
            .overridingErrorMessage("the fixture name must land before the projection legs")
            .isEqualTo(HttpStatus.OK)

        val afterEnsure = ensureChat(bob, alice.id)
        assertThat(afterEnsure.statusCode.is2xxSuccessful).isTrue
        val ensuredPeer = peerOf(objectMapper.readTree(afterEnsure.body!!))
        assertThat(ensuredPeer.displayNameText())
            .overridingErrorMessage("№11 peer must carry the profile displayName")
            .isEqualTo(PROFILE_NAME)
        assertConformsToSchema(ensuredPeer, PUBLIC_USER_SCHEMA)

        val listedPeer = peerOf(chatListItemOf(listChats(bob), chatId))
        assertThat(listedPeer.displayNameText())
            .overridingErrorMessage("№12 peer must carry the profile displayName")
            .isEqualTo(PROFILE_NAME)
        assertConformsToSchema(listedPeer, PUBLIC_USER_SCHEMA)

        val fetchedPeer = peerOf(objectMapper.readTree(getChat(bob, chatId).body!!))
        assertThat(fetchedPeer.displayNameText())
            .overridingErrorMessage("№13 peer must carry the profile displayName")
            .isEqualTo(PROFILE_NAME)
        assertConformsToSchema(fetchedPeer, PUBLIC_USER_SCHEMA)

        // №26: an undelivered message of the pair pulls the delta whose
        // peer renders through the same PublicUser projector.
        sendMessageOk(alice, chatId, "the message that creates the delta alice is the peer of")
        val sync = requestSync(bob, listOf(chatId to ZERO_CURSOR))
        assertThat(sync.statusCode)
            .overridingErrorMessage("№26 sync must answer 200, got <%s>: %s", sync.statusCode, sync.body)
            .isEqualTo(HttpStatus.OK)
        val deltaPeer = peerOf(deltaOf(objectMapper.readTree(sync.body!!), chatId))
        assertThat(deltaPeer.displayNameText())
            .overridingErrorMessage("the №26 delta peer must carry the profile displayName")
            .isEqualTo(PROFILE_NAME)
        assertConformsToSchema(deltaPeer, PUBLIC_USER_SCHEMA)
    }

    /**
     * FR-005 propagation beyond the direct chat: the №19 exact search
     * (observed by a THIRD party — the searcher needs no contact row)
     * and the №28 group member fragment (`UserWithAlias` = PublicUser +
     * the caller's alias slot) both carry the stored name; absent before
     * the name is set. The search semantics themselves (exact
     * username/email match) are NOT touched by the name (T017 guard).
     */
    @Test
    fun `display name surfaces in №19 search results and №28 group member fragments`() {
        val alice = messagingUser("maria")
        val bob = messagingUser("bob")
        val carol = messagingUser("carol")
        assertThat(addContact(bob, alice.id).statusCode.is2xxSuccessful)
            .overridingErrorMessage("the group fixture needs alice among bob's contacts")
            .isTrue
        val chatId = chatIdOf(createGroupOk(bob, GROUP_TITLE, memberUserIds = listOf(alice.id)))

        assertThat(foundUserOf(searchUsers(carol, alice.username)).hasDisplayName())
            .overridingErrorMessage("№19 must NOT carry displayName while the found user has no name (SC-007)")
            .isFalse
        assertThat(memberUserOf(getGroup(bob, chatId), alice.id).hasDisplayName())
            .overridingErrorMessage("№28 member must NOT carry displayName while the member has no name (SC-007)")
            .isFalse

        assertThat(updateProfile(alice, PROFILE_NAME).statusCode)
            .overridingErrorMessage("the fixture name must land before the surface legs")
            .isEqualTo(HttpStatus.OK)

        val found = foundUserOf(searchUsers(carol, alice.username))
        assertThat(found.displayNameText())
            .overridingErrorMessage("№19 found user must carry the profile displayName")
            .isEqualTo(PROFILE_NAME)
        assertConformsToSchema(found, USER_WITH_ALIAS_SCHEMA)

        val member = memberUserOf(getGroup(bob, chatId), alice.id)
        assertThat(member.displayNameText())
            .overridingErrorMessage("№28 member fragment must carry the profile displayName")
            .isEqualTo(PROFILE_NAME)
        assertConformsToSchema(member, USER_WITH_ALIAS_SCHEMA)
    }

    /**
     * №39 flood (research.md C2): the per-user 30/min bucket
     * `rl:user:profile:{userId}`. The drain burst must spend the FULL
     * allowance before any 429; like [ContactsIT][webchat.backend.contacts.ContactsIT],
     * the greedy 30/60s drip re-grants tokens mid-burst, so the first
     * refusal lands at attempt 31+ within `30 + elapsed/2 + 1` — never
     * at a fixed index. The refusal is the contract 429: problem+json
     * with `errors.displayName=[flood_limit]` and an integral
     * `Retry-After` of ≥1s; the bucket is strictly per-user, so a
     * DIFFERENT user updates right after undisturbed.
     */
    @Test
    fun `profile writes past the thirty-per-minute allowance are refused 429 flood_limit with Retry-After`() {
        val flooder = messagingUser("flooder")
        val bystander = messagingUser("bystander")

        var accepted = 0
        var rejection: ResponseEntity<String>? = null
        val startedAt = System.nanoTime()
        var attempt = 0
        while (rejection == null && attempt < FLOOD_BURST_ATTEMPT_CAP) {
            attempt += 1
            val response = updateProfile(flooder, "flood-name-$attempt")
            if (response.statusCode == HttpStatus.OK) {
                accepted += 1
            } else {
                rejection = response
            }
        }
        val elapsedSeconds = (System.nanoTime() - startedAt) / NANOS_PER_SECOND
        assertThat(rejection)
            .overridingErrorMessage(
                "the 30-writes-per-minute №39 bucket must refuse the burst of %d updates, " +
                    "but every attempt answered 200 — the flood limit is not enforced on №39",
                FLOOD_BURST_ATTEMPT_CAP,
            ).isNotNull
        val dripBound = FLOOD_WINDOW.toLong() + elapsedSeconds / 2 + FLOOD_DRIP_SLACK
        assertThat(accepted.toLong())
            .overridingErrorMessage(
                "the first refusal must land at write 31+ of the window and within the greedy-drip bound " +
                    "30+elapsed/2+1 (accepted=<%d>, elapsed=<%ds>, bound=<%d>)",
                accepted,
                elapsedSeconds,
                dripBound,
            ).isBetween(FLOOD_WINDOW.toLong(), dripBound)

        assertFloodRejection(rejection!!)
        assertThat(updateProfile(bystander, PROFILE_NAME).statusCode)
            .overridingErrorMessage("the №39 flood bucket is per-user — a different user must update undisturbed")
            .isEqualTo(HttpStatus.OK)
    }

    // ------------------------------------------------------------------
    // Contract №39/№10/№19/№26/№27/№28 call helpers (raw responses).
    // ------------------------------------------------------------------

    /** Contract №39 `PUT /api/v1/users/me/profile` — `displayName: value|null`. */
    private fun updateProfile(
        user: MessagingUser,
        displayName: String?,
    ): ResponseEntity<String> = putJson(PROFILE_PATH, mapOf(DISPLAY_NAME_FIELD to displayName), user)

    /** Contract №39 with the field OMITTED entirely — the «absence = reset» leg. */
    private fun updateProfileOmittingField(user: MessagingUser): ResponseEntity<String> =
        putJson(PROFILE_PATH, emptyMap(), user)

    /** Contract №10 `GET /api/v1/users/me` — raw response. */
    private fun getMe(user: MessagingUser): ResponseEntity<String> = exchangeAuthed(HttpMethod.GET, ME_PATH, user)

    /** Contract №19 `GET /api/v1/users/search?query=` — raw response (exact username match). */
    private fun searchUsers(
        user: MessagingUser,
        query: String,
    ): ResponseEntity<String> {
        val path =
            UriComponentsBuilder
                .fromPath(SEARCH_PATH)
                .queryParam(QUERY_FIELD, query)
                .build()
                .toUriString()
        return exchangeAuthed(HttpMethod.GET, path, user)
    }

    /** Contract №21 `POST /api/v1/contacts` — the group-fixture prerequisite. */
    private fun addContact(
        user: MessagingUser,
        userId: UUID,
    ): ResponseEntity<String> = postJson(CONTACTS_PATH, mapOf(USER_ID_FIELD to userId.toString()), user)

    /** Contract №27 `POST /api/v1/groups` — raw response. */
    private fun createGroup(
        owner: MessagingUser,
        title: String,
        memberUserIds: List<UUID>,
    ): ResponseEntity<String> =
        postJson(
            GROUPS_PATH,
            buildMap {
                put(TITLE_FIELD, title)
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
        memberUserIds: List<UUID>,
    ): JsonNode {
        val response = createGroup(owner, title, memberUserIds)
        assertThat(response.statusCode)
            .overridingErrorMessage("№27 create must answer 201, got <%s>: %s", response.statusCode, response.body)
            .isEqualTo(HttpStatus.CREATED)
        return objectMapper.readTree(response.body!!)
    }

    /** Contract №28 `GET /api/v1/groups/{chatId}` — raw response. */
    private fun getGroup(
        user: MessagingUser,
        chatId: UUID,
    ): ResponseEntity<String> = exchangeAuthed(HttpMethod.GET, "$GROUPS_PATH/$chatId", user)

    /** Contract №26 `POST /api/v1/users/me/sync` — cursors as `chatId to upToSeq` pairs. */
    private fun requestSync(
        user: MessagingUser,
        cursors: List<Pair<UUID, Long>>,
    ): ResponseEntity<String> {
        val payload =
            mapOf(
                "cursors" to
                    cursors.map { (chatId, upToSeq) ->
                        mapOf("chatId" to chatId.toString(), "upToSeq" to upToSeq)
                    },
            )
        return postJson(SYNC_PATH, payload, user)
    }

    // ------------------------------------------------------------------
    // Body navigation and assertions.
    // ------------------------------------------------------------------

    private fun peerOf(view: JsonNode): JsonNode = view["peer"]

    private fun bodyTree(response: ResponseEntity<String>): JsonNode = objectMapper.readTree(response.body!!)

    private fun chatListItemOf(
        response: ResponseEntity<String>,
        chatId: UUID,
    ): JsonNode =
        objectMapper.readTree(response.body!!)["chats"].firstOrNull { it["chatId"].asText() == chatId.toString() }
            ?: error("the №12 list must carry the item of chat $chatId in ${response.body}")

    private fun deltaOf(
        response: JsonNode,
        chatId: UUID,
    ): JsonNode =
        response["chats"].firstOrNull { it["chatId"].asText() == chatId.toString() }
            ?: error("the №26 sync answer must carry the delta of chat $chatId in $response")

    private fun foundUserOf(response: ResponseEntity<String>): JsonNode {
        val users = objectMapper.readTree(response.body!!)["users"]
        assertThat(users.size())
            .overridingErrorMessage(
                "the №19 exact search by username must find exactly the addressee: %s",
                response.body,
            ).isEqualTo(1)
        return users[0]
    }

    private fun memberUserOf(
        response: ResponseEntity<String>,
        userId: UUID,
    ): JsonNode {
        assertThat(response.statusCode)
            .overridingErrorMessage("№28 must answer 200, got <%s>: %s", response.statusCode, response.body)
            .isEqualTo(HttpStatus.OK)
        val member =
            objectMapper.readTree(response.body!!)["members"].firstOrNull {
                it["user"]["id"].asText() == userId.toString()
            }
        assertThat(member)
            .overridingErrorMessage("№28 must list the member <%s>: %s", userId, response.body)
            .isNotNull
        return member!!["user"]
    }

    private fun chatIdOf(groupView: JsonNode): UUID = UUID.fromString(groupView["chatId"].asText())

    private fun JsonNode.hasDisplayName(): Boolean = has(DISPLAY_NAME_FIELD) && !this[DISPLAY_NAME_FIELD].isNull

    private fun JsonNode.displayNameText(): String? =
        if (hasDisplayName()) {
            this[DISPLAY_NAME_FIELD].asText()
        } else {
            null
        }

    /** The №39 400 of api-contract.md: problem+json with `errors.displayName=[invalid_display_name]`. */
    private fun assertInvalidDisplayName(
        response: ResponseEntity<String>,
        case: String,
    ) {
        assertThat(response.statusCode)
            .overridingErrorMessage("%s must be refused 400, got <%s>: %s", case, response.statusCode, response.body)
            .isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.headers.contentType?.toString())
            .overridingErrorMessage("the №39 refusal must be RFC 9457 application/problem+json")
            .contains(PROBLEM_JSON_MEDIA_TYPE)
        val codes =
            objectMapper
                .readTree(response.body!!)["errors"]
                ?.get(DISPLAY_NAME_FIELD)
                ?.map { it.asText() }
                .orEmpty()
        assertThat(codes)
            .overridingErrorMessage(
                "the problem must carry errors.%s=[%s], got <%s> in %s",
                DISPLAY_NAME_FIELD,
                INVALID_DISPLAY_NAME,
                codes,
                response.body,
            ).containsExactly(INVALID_DISPLAY_NAME)
    }

    /** №39 429 of api-contract.md: `errors.displayName=[flood_limit]` + integral `Retry-After` ≥ 1s. */
    private fun assertFloodRejection(rejection: ResponseEntity<String>) {
        assertThat(rejection.statusCode)
            .overridingErrorMessage(
                "the №39 flood refusal must be 429, got <%s>: %s",
                rejection.statusCode,
                rejection.body,
            ).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
        assertThat(rejection.headers.contentType?.toString())
            .overridingErrorMessage("the №39 flood refusal must be RFC 9457 application/problem+json")
            .contains(PROBLEM_JSON_MEDIA_TYPE)
        val retryAfterHeader = rejection.headers.getFirst(HttpHeaders.RETRY_AFTER)
        val retryAfterSeconds = retryAfterHeader?.trim()?.toLongOrNull()
        assertThat(retryAfterSeconds)
            .overridingErrorMessage(
                "Retry-After must be integral seconds ≥ 1 (openapi №39), got <%s>",
                retryAfterHeader,
            ).isNotNull
        assertThat(retryAfterSeconds!!)
            .overridingErrorMessage(
                "Retry-After must be within one refill window (≥1s, ≤60s), got <%s>",
                retryAfterHeader,
            ).isBetween(RETRY_AFTER_FLOOR_SECONDS, RETRY_AFTER_CEILING_SECONDS)
        val codes =
            objectMapper
                .readTree(rejection.body!!)["errors"]
                ?.get(DISPLAY_NAME_FIELD)
                ?.map { it.asText() }
                .orEmpty()
        assertThat(codes)
            .overridingErrorMessage(
                "the problem must carry errors.%s=[%s], got <%s> in %s",
                DISPLAY_NAME_FIELD,
                FLOOD_LIMIT,
                codes,
                rejection.body,
            ).containsExactly(FLOOD_LIMIT)
    }

    // ------------------------------------------------------------------
    // Live-contract schema conformance (the RealtimeSseIT T064 pattern):
    // the asserted payloads must satisfy the very schemas of
    // contracts/openapi.yaml instead of a hand-copied field list
    // (constitution IV API-First). The validator covers the JSON Schema
    // subset those schemas use: type (object/string/integer), required,
    // properties, additionalProperties: false, $ref, format
    // (uuid/date-time), maxLength, minimum.
    // ------------------------------------------------------------------

    private fun assertConformsToSchema(
        payload: JsonNode,
        schemaName: String,
    ) {
        val schema = openApiContract["components"]["schemas"][schemaName]
        if (schema !is ObjectNode) {
            throw AssertionError(
                "contracts/openapi.yaml must keep components.schemas.$schemaName — " +
                    "the 008a projections are declared there (api-contract.md §2), got: ${schema.nodeType}",
            )
        }
        val violations = mutableListOf<String>()
        validateAgainst(payload, schema, "#", violations)
        assertThat(violations)
            .overridingErrorMessage(
                "payload violates components.schemas.%s of contracts/openapi.yaml: %s",
                schemaName,
                violations,
            ).isEmpty()
    }

    private fun validateAgainst(
        node: JsonNode,
        schema: JsonNode,
        path: String,
        violations: MutableList<String>,
    ) {
        val ref = schema["\$ref"]?.takeIf { it.isTextual }
        if (ref != null) {
            val target = resolveLocalRef(ref.asText(), path, violations)
            if (target != null) validateAgainst(node, target, path, violations)
            return
        }
        when (schema["type"]?.takeIf { it.isTextual }?.asText()) {
            "object" -> validateObject(node, schema, path, violations)
            "string" -> validateString(node, schema, path, violations)
            "integer" -> validateInteger(node, schema, path, violations)
            // a schema without a type imposes no structural constraint here
            else -> Unit
        }
    }

    private fun resolveLocalRef(
        ref: String,
        path: String,
        violations: MutableList<String>,
    ): JsonNode? {
        // JsonNode.at wants a JSON Pointer: keep the leading '/' of "#/components/...".
        val target = openApiContract.at(ref.removePrefix(REF_PREFIX)).takeUnless { it.isMissingNode }
        return when {
            !ref.startsWith(REF_PREFIX) -> {
                violations.add("$path: only local schema refs are supported, got <$ref>")
                null
            }
            target == null -> {
                violations.add("$path: unresolvable schema ref <$ref>")
                null
            }
            else -> target
        }
    }

    private fun validateObject(
        node: JsonNode,
        schema: JsonNode,
        path: String,
        violations: MutableList<String>,
    ) {
        if (!node.isObject) {
            violations.add("$path: expected an object, got <${node.nodeType}>")
            return
        }
        schema["required"]?.takeIf { it.isArray }?.forEach { requiredField ->
            if (!node.has(requiredField.asText())) {
                violations.add("$path: required field <${requiredField.asText()}> is missing")
            }
        }
        val additionalForbidden =
            schema["additionalProperties"]?.takeIf { it.isBoolean }?.asBoolean() == false
        val properties = schema["properties"]?.takeIf { it.isObject }
        node.fieldNames().asSequence().forEach { field ->
            val propertySchema = properties?.get(field)
            when {
                propertySchema != null && !propertySchema.isMissingNode ->
                    validateAgainst(node[field], propertySchema, "$path/$field", violations)
                additionalForbidden ->
                    violations.add("$path/$field: not declared by the schema and additionalProperties is false")
                else -> Unit
            }
        }
    }

    private fun validateString(
        node: JsonNode,
        schema: JsonNode,
        path: String,
        violations: MutableList<String>,
    ) {
        if (!node.isTextual) {
            violations.add("$path: expected a string, got <${node.nodeType}>")
            return
        }
        val text = node.asText()
        val maxLength = schema["maxLength"]?.takeIf { it.isInt }?.asInt()
        if (maxLength != null && text.length > maxLength) {
            violations.add("$path: <${text.length}> chars exceeds maxLength <$maxLength>")
        }
        when (schema["format"]?.takeIf { it.isTextual }?.asText()) {
            "uuid" ->
                if (runCatching { UUID.fromString(text) }.isFailure) {
                    violations.add("$path: <$text> is not a uuid")
                }
            "date-time" ->
                if (runCatching { OffsetDateTime.parse(text) }.isFailure) {
                    violations.add("$path: <$text> is not an RFC 3339 date-time")
                }
        }
    }

    private fun validateInteger(
        node: JsonNode,
        schema: JsonNode,
        path: String,
        violations: MutableList<String>,
    ) {
        if (!node.isIntegralNumber || !node.canConvertToLong()) {
            violations.add("$path: expected an int64 integer, got <${node.asText()}>")
            return
        }
        val minimum = schema["minimum"]?.takeIf { it.isIntegralNumber }?.asLong()
        if (minimum != null && node.asLong() < minimum) {
            violations.add("$path: <${node.asLong()}> is below the schema minimum <$minimum>")
        }
    }

    // ------------------------------------------------------------------
    // Raw HTTP legs.
    // ------------------------------------------------------------------

    private fun putJson(
        path: String,
        payload: Map<String, Any?>,
        user: MessagingUser,
    ): ResponseEntity<String> =
        restTemplate.exchange(
            path,
            HttpMethod.PUT,
            HttpEntity(payload, jsonHeaders(user)),
            String::class.java,
        )

    private fun postJson(
        path: String,
        payload: Map<String, Any?>,
        user: MessagingUser,
    ): ResponseEntity<String> =
        restTemplate.postForEntity(path, HttpEntity(payload, jsonHeaders(user)), String::class.java)

    private fun exchangeAuthed(
        method: HttpMethod,
        path: String,
        user: MessagingUser,
    ): ResponseEntity<String> =
        restTemplate.exchange(
            path,
            method,
            HttpEntity<Void>(null, jsonHeaders(user)),
            String::class.java,
        )

    private fun jsonHeaders(user: MessagingUser): HttpHeaders =
        HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            setBearerAuth(user.accessToken)
        }

    private companion object {
        const val PROFILE_PATH = "/api/v1/users/me/profile"
        const val ME_PATH = "/api/v1/users/me"
        const val SEARCH_PATH = "/api/v1/users/search"
        const val CONTACTS_PATH = "/api/v1/contacts"
        const val GROUPS_PATH = "/api/v1/groups"
        const val SYNC_PATH = "/api/v1/users/me/sync"

        const val DISPLAY_NAME_FIELD = "displayName"
        const val QUERY_FIELD = "query"
        const val USER_ID_FIELD = "userId"
        const val TITLE_FIELD = "title"
        const val MEMBER_USER_IDS_FIELD = "memberUserIds"

        const val INVALID_DISPLAY_NAME = "invalid_display_name"
        const val FLOOD_LIMIT = "flood_limit"
        const val PROBLEM_JSON_MEDIA_TYPE = "application/problem+json"

        const val PUBLIC_USER_SCHEMA = "PublicUser"
        const val USER_WITH_ALIAS_SCHEMA = "UserWithAlias"
        const val REF_PREFIX = "#"

        const val PROFILE_NAME = "Мария"
        const val UNICODE_NAME = "🙂Мария🙂"
        const val BLANK_NAME = " \t  "
        const val NAME_MAX_LENGTH = 64
        val BOUNDARY_NAME: String = "м".repeat(NAME_MAX_LENGTH)
        val OVERSIZED_NAME: String = "м".repeat(NAME_MAX_LENGTH + 1)
        const val GROUP_TITLE = "008a profile-name surfaces"

        /** api-contract.md §1 №39: 30 profile writes per minute per user. */
        const val FLOOD_WINDOW = 30
        const val FLOOD_BURST_ATTEMPT_CAP = 40
        const val FLOOD_DRIP_SLACK = 1L
        const val RETRY_AFTER_FLOOR_SECONDS = 1L
        const val RETRY_AFTER_CEILING_SECONDS = 60L
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val ZERO_CURSOR = 0L

        /** `backend/` (Gradle) and the repo root (IDE runs) in order. */
        val CONTRACT_LOCATIONS: List<Path> =
            listOf(Path.of("../contracts/openapi.yaml"), Path.of("contracts/openapi.yaml"))

        val contractJson: ObjectMapper = ObjectMapper()

        /** contracts/openapi.yaml parsed once per JVM (the T064 conformance pattern). */
        val openApiContract: JsonNode by lazy { loadOpenApiContract() }

        /**
         * The public contract of the monorepo — the single source of truth
         * the 008a projections are asserted against. Parsed through
         * SnakeYAML (on the test classpath via Spring Boot's yaml support)
         * and mapped onto a Jackson tree.
         */
        private fun loadOpenApiContract(): JsonNode {
            val contract =
                CONTRACT_LOCATIONS.firstOrNull { Files.isRegularFile(it) }
                    ?: throw IllegalStateException(
                        "contracts/openapi.yaml not found at ${CONTRACT_LOCATIONS.joinToString()} — " +
                            "the T009 conformance check must read the live public contract",
                    )
            return contract.toFile().inputStream().use { input ->
                val yamlRoot: Any? = Yaml().load(input)
                contractJson.valueToTree<JsonNode>(yamlRoot)
            }
        }
    }
}
