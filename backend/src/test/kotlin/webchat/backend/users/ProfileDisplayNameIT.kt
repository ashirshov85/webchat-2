package webchat.backend.users

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.client.RestTemplate
import org.springframework.web.util.UriComponentsBuilder
import org.yaml.snakeyaml.Yaml
import webchat.backend.chats.MessagingTestSupport
import webchat.backend.sso.MockIdP
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

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
 * T012a (tasks.md 008b Phase 4, US2, contract 0.10.0 §2): №10 grows
 * the additive REQUIRED `hasPassword` boolean — inline `allOf` over
 * `$ref PublicUser` (research.md 008b §4):
 *  * the `true` leg — a password account grown through the real 002
 *    flow answers `hasPassword=true` and its whole body satisfies the
 *    LIVE inline №10 schema of contracts/openapi.yaml;
 *  * the `false` leg — the password-less JIT account of SSO 003
 *    (`password_hash IS NULL`, provisioned by a REAL MockIdP login —
 *    SsoFlowIT/PasswordChangeIT conventions) answers `hasPassword=false`
 *    under the same live schema;
 *  * FR-004 suppression — the peer fragments of №11/№12/№13 and the
 *    №26 sync delta peer, the №19 exact search hit and the №28 group
 *    member fragment NEVER carry `hasPassword`: the JIT origin of an
 *    account must not leak to other users.
 *
 * NOTE (TDD, constitution VI): written BEFORE T011/T012 (and the
 * projection legs of T015–T017) land — until the №39 route exists
 * every PUT here answers 404/405 and the projection legs never see a
 * displayName, so every method fails (RED) by design; T026 acceptance
 * is this class green together with ContactAliasIT. The T012a legs
 * follow the same rule: until T014 lands `MeUserResponse` the №10
 * body carries no `hasPassword`, so both required-field legs (and the
 * switched №10 conformance leg of the first test) fail (RED) by design.
 */
@Suppress("TooManyFunctions", "LargeClass") // T009: one helper per contract operation, one method per US1 rule
class ProfileDisplayNameIT(
    @Autowired private val restTemplate: TestRestTemplate,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) : MessagingTestSupport() {
    @BeforeEach
    fun resetIdp() {
        mockIdP.reset()
    }

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
        // 008b T012a: №10 now grows the additive REQUIRED hasPassword — the
        // plain PublicUser leg is replaced by the LIVE inline allOf schema.
        assertMeConformsToLiveContract(objectMapper.readTree(baseline.body))

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

    /**
     * T012a `true` leg (008b US2, contract 0.10.0 §2): a password
     * account grown through the REAL 002 flow must carry the additive
     * REQUIRED `hasPassword=true`, and the whole №10 body must satisfy
     * the LIVE inline allOf(PublicUser, hasPassword) schema of
     * contracts/openapi.yaml (research.md 008b §4). RED until T014
     * lands `MeUserResponse` (constitution VI).
     */
    @Test
    fun `me carries the required hasPassword true for a password account`() {
        val alice = messagingUser("pwdholder")

        val response = getMe(alice)
        assertThat(response.statusCode)
            .overridingErrorMessage("№10 must answer 200, got <%s>: %s", response.statusCode, response.body)
            .isEqualTo(HttpStatus.OK)
        val me = bodyTree(response)
        assertMeConformsToLiveContract(me)
        assertThat(me.hasPasswordFlag())
            .overridingErrorMessage(
                "№10 must carry the required boolean hasPassword=true for a password account, got <%s> in %s",
                me[HAS_PASSWORD_FIELD],
                response.body,
            ).isEqualTo(true)
    }

    /**
     * T012a `false` leg: the password-less JIT account of SSO 003 —
     * provisioned by a REAL MockIdP login (SsoFlowIT/PasswordChangeIT
     * conventions), so `users.password_hash IS NULL` while the account
     * is `active`. №10 must answer `hasPassword=false` under the same
     * live allOf schema — the profile form gates on exactly this
     * signal (FR-004). RED until T014.
     */
    @Test
    fun `me answers hasPassword false for the passwordless jit sso account`() {
        mockIdP.setClaims(MockIdP.ControlledClaims(subject = SUBJECT_JIT, email = EMAIL_JIT, emailVerified = true))
        val jit = performSsoLogin()

        // sanity: the 003 JIT shape — active account, password_hash IS NULL
        val jitRow = userRowOf(jit.id)
        assertThat(jitRow)
            .overridingErrorMessage("the JIT fixture <%s> must exist in users", jit.username)
            .isNotNull
        assertThat(jitRow!!["status"]).isEqualTo("active")
        assertThat(jitRow["password_hash"]).isNull()

        val response = getMe(jit)
        assertThat(response.statusCode)
            .overridingErrorMessage(
                "№10 must answer 200 for the JIT session, got <%s>: %s",
                response.statusCode,
                response.body,
            ).isEqualTo(HttpStatus.OK)
        val me = bodyTree(response)
        assertMeConformsToLiveContract(me)
        assertThat(me.hasPasswordFlag())
            .overridingErrorMessage(
                "№10 must answer hasPassword=false for the password-less JIT account, got <%s> in %s",
                me[HAS_PASSWORD_FIELD],
                response.body,
            ).isEqualTo(false)
    }

    /**
     * T012a suppression leg (FR-004): the JIT marker must NEVER leak
     * beyond №10 — the peer fragments of №11 (ensure), №12 (list) and
     * №13 (get) plus the №26 sync delta peer, the №19 exact search hit
     * and the №28 group member fragment all render through the shared
     * `PublicUser`/`UserWithAlias` projectors and stay free of
     * `hasPassword` (research.md 008b §4: the field inside `PublicUser`
     * would disclose the JIT origin of an account to OTHER users).
     * This leg guards the T014 implementation from exactly that leak.
     */
    @Test
    fun `hasPassword stays off the peer search and member surfaces`() {
        val (alice, bob) = messagingPair()
        val carol = messagingUser("carol")
        val chatId = ensureChatOk(bob, alice.id)

        // a full projection: the surfaces carry every PublicUser field they may carry
        assertThat(updateProfile(alice, PROFILE_NAME).statusCode)
            .overridingErrorMessage("the fixture name must land before the suppression legs")
            .isEqualTo(HttpStatus.OK)

        assertThat(peerOf(objectMapper.readTree(ensureChat(bob, alice.id).body!!)).has(HAS_PASSWORD_FIELD))
            .overridingErrorMessage("the №11 peer must NOT carry hasPassword (FR-004)")
            .isFalse
        assertThat(peerOf(chatListItemOf(listChats(bob), chatId)).has(HAS_PASSWORD_FIELD))
            .overridingErrorMessage("the №12 peer must NOT carry hasPassword (FR-004)")
            .isFalse
        assertThat(peerOf(objectMapper.readTree(getChat(bob, chatId).body!!)).has(HAS_PASSWORD_FIELD))
            .overridingErrorMessage("the №13 peer must NOT carry hasPassword (FR-004)")
            .isFalse

        // №26: an undelivered message pulls the delta whose peer renders at read time
        sendMessageOk(alice, chatId, "the message that creates the hasPassword suppression delta")
        val sync = requestSync(bob, listOf(chatId to ZERO_CURSOR))
        assertThat(sync.statusCode)
            .overridingErrorMessage("№26 sync must answer 200, got <%s>: %s", sync.statusCode, sync.body)
            .isEqualTo(HttpStatus.OK)
        assertThat(peerOf(deltaOf(objectMapper.readTree(sync.body!!), chatId)).has(HAS_PASSWORD_FIELD))
            .overridingErrorMessage("the №26 delta peer must NOT carry hasPassword (FR-004)")
            .isFalse

        // №19 search (observed by a third party) and №28 group member fragment
        assertThat(foundUserOf(searchUsers(carol, alice.username)).has(HAS_PASSWORD_FIELD))
            .overridingErrorMessage("the №19 found user must NOT carry hasPassword (FR-004)")
            .isFalse
        assertThat(addContact(bob, alice.id).statusCode.is2xxSuccessful)
            .overridingErrorMessage("the №28 fixture needs alice among bob's contacts")
            .isTrue
        val groupChatId = chatIdOf(createGroupOk(bob, GROUP_TITLE, memberUserIds = listOf(alice.id)))
        assertThat(memberUserOf(getGroup(bob, groupChatId), alice.id).has(HAS_PASSWORD_FIELD))
            .overridingErrorMessage("the №28 member fragment must NOT carry hasPassword (FR-004)")
            .isFalse
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

    /** The required №10 boolean of 0.10.0 — `null` while absent/non-boolean (the RED shape before T014). */
    private fun JsonNode.hasPasswordFlag(): Boolean? =
        takeIf { has(HAS_PASSWORD_FIELD) && this[HAS_PASSWORD_FIELD].isBoolean }
            ?.get(HAS_PASSWORD_FIELD)
            ?.asBoolean()

    /** The raw `users` row of a fixture (PasswordResetIT conventions) — the T012a JIT sanity legs. */
    private fun userRowOf(userId: UUID): Map<String, Any>? =
        jdbcTemplate
            .queryForList(
                """
                SELECT id, username, email, password_hash, status::text AS status
                FROM users WHERE id = ?
                """.trimIndent(),
                userId,
            ).firstOrNull()

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
        allOfDeclared: Set<String>? = null,
    ) {
        val ref = schema["\$ref"]?.takeIf { it.isTextual }
        if (ref != null) {
            val target = resolveLocalRef(ref.asText(), path, violations)
            if (target != null) validateAgainst(node, target, path, violations, allOfDeclared)
            return
        }
        // 008b T012a: the №10 answer is the inline allOf(PublicUser, hasPassword)
        // composition — every branch applies to the very same payload.
        val composition = schema["allOf"]?.takeIf { it.isArray }
        if (composition != null) {
            validateAllOf(node, composition, path, violations)
            return
        }
        when (schema["type"]?.takeIf { it.isTextual }?.asText()) {
            "object" -> validateObject(node, schema, path, violations, allOfDeclared)
            "string" -> validateString(node, schema, path, violations)
            "integer" -> validateInteger(node, schema, path, violations)
            "boolean" -> validateBoolean(node, path, violations)
            // a schema without a type imposes no structural constraint here
            else -> Unit
        }
    }

    /**
     * allOf with the OpenAPI extension semantics: every branch contributes
     * its required/property constraints, while the `additionalProperties:
     * false` gate of ANY branch admits fields declared by ANY branch —
     * the merged-object reading response validators apply to the canonical
     * `allOf($ref PublicUser, extension)` pattern of contract 0.10.0 №10.
     */
    private fun validateAllOf(
        node: JsonNode,
        branches: JsonNode,
        path: String,
        violations: MutableList<String>,
    ) {
        val declared = mutableSetOf<String>()
        branches.forEach { branch -> declared.addAll(declaredPropertiesOf(branch, path, violations)) }
        branches.forEach { branch -> validateAgainst(node, branch, path, violations, declared) }
    }

    /** The property names a branch declares — follows a local `$ref` (branch 1 of the №10 composition). */
    private fun declaredPropertiesOf(
        schema: JsonNode,
        path: String,
        violations: MutableList<String>,
    ): Set<String> {
        val ref = schema["\$ref"]?.takeIf { it.isTextual }
        val target =
            if (ref != null) {
                resolveLocalRef(ref.asText(), path, violations)
            } else {
                schema
            }
        return target
            ?.get("properties")
            ?.takeIf { it.isObject }
            ?.fieldNames()
            ?.asSequence()
            ?.toSet()
            .orEmpty()
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
        allOfDeclared: Set<String>? = null,
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
                additionalForbidden && (allOfDeclared == null || field !in allOfDeclared) ->
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

    private fun validateBoolean(
        node: JsonNode,
        path: String,
        violations: MutableList<String>,
    ) {
        if (!node.isBoolean) {
            violations.add("$path: expected a boolean, got <${node.nodeType}>")
        }
    }

    // ------------------------------------------------------------------
    // 008b T012a: the LIVE inline №10 schema of contract 0.10.0 — the
    // allOf(PublicUser, hasPassword) composition lives INLINE in the
    // path item (NOT under components), so the conformance legs
    // navigate the path itself; every '/' of a segment is JSON-Pointer
    // escaped as '~1'.
    // ------------------------------------------------------------------

    private fun meResponseSchema(): JsonNode {
        val schema =
            openApiContract.at(
                "/paths/~1api~1v1~1users~1me/get/responses/200/content/application~1json/schema",
            )
        if (schema !is ObjectNode) {
            throw AssertionError(
                "contracts/openapi.yaml must keep the №10 200 answer as the inline " +
                    "allOf(PublicUser, hasPassword) schema of 0.10.0 (research.md 008b §4), got: ${schema.nodeType}",
            )
        }
        return schema
    }

    private fun assertMeConformsToLiveContract(payload: JsonNode) {
        val violations = mutableListOf<String>()
        validateAgainst(payload, meResponseSchema(), "#", violations)
        assertThat(violations)
            .overridingErrorMessage(
                "the №10 body violates the live inline me-schema of contracts/openapi.yaml 0.10.0: %s",
                violations,
            ).isEmpty()
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

    // ------------------------------------------------------------------
    // 008b T012a: the SSO leg (SsoFlowIT/PasswordChangeIT conventions) —
    // the loopback MockIdP drives a REAL 003 login so the hasPassword
    // `false` leg observes a genuine password-less JIT session.
    // ------------------------------------------------------------------

    /** A JSON POST with an explicit fixture source IP — the SSO legs keep their own per-IP buckets. */
    private fun postJsonWithSourceIp(
        path: String,
        payload: Map<String, Any?>,
        sourceIp: String,
    ): ResponseEntity<String> =
        restTemplate.postForEntity(
            path,
            HttpEntity(
                payload,
                HttpHeaders().apply {
                    contentType = MediaType.APPLICATION_JSON
                    set(X_FORWARDED_FOR_HEADER, sourceIp)
                },
            ),
            String::class.java,
        )

    /**
     * TestRestTemplate's JDK client follows redirects, which would chase the
     * 302 Locations straight into unreachable redirect targets; the IdP and
     * callback assertions need the raw FOUND responses (SsoFlowIT precedent).
     */
    private val noRedirectClient: RestTemplate =
        RestTemplate(
            object : SimpleClientHttpRequestFactory() {
                override fun prepareConnection(
                    connection: HttpURLConnection,
                    httpMethod: String,
                ) {
                    super.prepareConnection(connection, httpMethod)
                    connection.instanceFollowRedirects = false
                }
            },
        )

    /** The REAL 003 login loop through the loopback MockIdP → a password-less JIT fixture. */
    private fun performSsoLogin(): MessagingUser {
        val authorize =
            postJsonWithSourceIp(SSO_AUTHORIZE_PATH, mapOf(PROVIDER_ID_FIELD to PROVIDER_ID), nextSsoIp())
        assertThat(authorize.statusCode)
            .overridingErrorMessage("sso/authorize must answer 200, got <%s>: %s", authorize.statusCode, authorize.body)
            .isEqualTo(HttpStatus.OK)
        val authorizationUrl = objectMapper.readTree(authorize.body)["authorizationUrl"].asText()

        val idpRedirect = noRedirectClient.getForEntity(URI.create(authorizationUrl), String::class.java)
        assertThat(idpRedirect.statusCode)
            .overridingErrorMessage("the IdP authorize leg must redirect 302, got <%s>", idpRedirect.statusCode)
            .isEqualTo(HttpStatus.FOUND)
        val idpParameters = queryParametersOf(idpRedirect.headers.location.toString())

        val callbackUrl =
            UriComponentsBuilder
                .fromHttpUrl(rootUri() + SSO_CALLBACK_PATH)
                .queryParam(STATE_PARAMETER, idpParameters.getValue(STATE_PARAMETER))
                .queryParam(CODE_PARAMETER, idpParameters.getValue(CODE_PARAMETER))
                .build()
                .toUriString()
        val callbackResponse = noRedirectClient.getForEntity(URI.create(callbackUrl), String::class.java)
        assertThat(callbackResponse.statusCode)
            .overridingErrorMessage("sso/callback must redirect 302, got <%s>", callbackResponse.statusCode)
            .isEqualTo(HttpStatus.FOUND)
        val handshakeCode = queryParametersOf(callbackResponse.headers.location.toString()).getValue(CODE_PARAMETER)

        val tokenExchange = postJsonWithSourceIp(SSO_TOKEN_PATH, mapOf(CODE_PARAMETER to handshakeCode), nextSsoIp())
        assertThat(tokenExchange.statusCode)
            .overridingErrorMessage(
                "sso/token must answer 200, got <%s>: %s",
                tokenExchange.statusCode,
                tokenExchange.body,
            ).isEqualTo(HttpStatus.OK)
        val body = objectMapper.readTree(tokenExchange.body)
        return MessagingUser(
            id = UUID.fromString(body["user"]["id"].asText()),
            username = body["user"]["username"].asText(),
            email = body["user"]["email"].asText(),
            accessToken = body["accessToken"].asText(),
            refreshToken = body["refreshToken"].asText(),
        )
    }

    private fun rootUri(): String = restTemplate.rootUri.removeSuffix("/")

    private fun queryParametersOf(url: String): Map<String, String> =
        UriComponentsBuilder
            .fromUriString(url)
            .build()
            .queryParams
            .map { (name, values) -> name to values.first() }
            .toMap()

    private fun nextSsoIp(): String = "$SSO_IP_PREFIX${ssoIpCounter.incrementAndGet()}"

    companion object {
        /** The same MockIdP controller class as the SSO ITs, driven directly by this suite (T012a). */
        val mockIdP = MockIdP(ObjectMapper())

        /**
         * Loopback mount of the MockIdP whose base URI is known BEFORE the
         * Spring context starts — the precondition for binding
         * `sso.providers.*` to absolute IdP endpoints (SsoFlowIT /
         * PasswordChangeIT precedent).
         */
        private val idpServer = IdpLoopbackMount(mockIdP)

        @JvmStatic
        @DynamicPropertySource
        fun ssoProviderProperties(registry: DynamicPropertyRegistry) {
            registry.add("sso.providers.$PROVIDER_ID.display-name") { "Profile DisplayName IT IdP" }
            registry.add("sso.providers.$PROVIDER_ID.client-id") { MockIdP.DEFAULT_CLIENT_ID }
            registry.add("sso.providers.$PROVIDER_ID.client-secret") { MockIdP.DEFAULT_CLIENT_SECRET }
            registry.add("sso.providers.$PROVIDER_ID.authorization-uri") { "${idpServer.baseUri}/mock-idp/authorize" }
            registry.add("sso.providers.$PROVIDER_ID.token-uri") { "${idpServer.baseUri}/mock-idp/token" }
            registry.add("sso.providers.$PROVIDER_ID.jwks-uri") { "${idpServer.baseUri}/mock-idp/jwks" }
        }

        @JvmStatic
        @AfterAll
        fun stopIdpServer() {
            idpServer.stop()
        }

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

        // 008b T012a: the additive №10 field and the SSO fixture of the JIT leg
        const val HAS_PASSWORD_FIELD = "hasPassword"
        const val PROVIDER_ID = "mock-idp"
        const val PROVIDER_ID_FIELD = "providerId"
        const val SUBJECT_JIT = "subject-pdit-jit-4r8s"
        const val EMAIL_JIT = "pdit.jit@example.com"
        const val X_FORWARDED_FOR_HEADER = "X-Forwarded-For"

        /** 198.18.29.x — dedicated SSO-fixture IPs of this suite (PasswordChangeIT owns .20–.28). */
        const val SSO_IP_PREFIX = "198.18.29."
        val ssoIpCounter = AtomicInteger()

        const val STATE_PARAMETER = "state"
        const val CODE_PARAMETER = "code"

        const val SSO_AUTHORIZE_PATH = "/api/v1/auth/sso/authorize"
        const val SSO_CALLBACK_PATH = "/api/v1/auth/sso/callback"
        const val SSO_TOKEN_PATH = "/api/v1/auth/sso/token"

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

/**
 * Loopback HTTP mount of the very same [MockIdP] controller class the SSO ITs
 * use (SsoFlowIT's MockIdPLoopbackServer precedent, reduced to the three OIDC
 * endpoints this suite drives — the PasswordChangeIT copy): the provider
 * endpoints must be absolute URIs known while `@DynamicPropertySource` binds
 * `sso.providers.*`, long before the random application port becomes
 * observable. Requests are translated into [MockHttpServletRequest] and
 * dispatched to the controller methods, responses are translated back —
 * byte-for-byte the same behavior as the in-context bean, without a second
 * Spring context.
 */
private class IdpLoopbackMount(
    private val idp: MockIdP,
) {
    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, THREAD_NAME).apply { isDaemon = true }
        }

    private val server: HttpServer = HttpServer.create(InetSocketAddress(LOOPBACK_HOST, 0), BACKLOG)

    /** Absolute base URI (e.g. `http://127.0.0.1:39291`) known before the Spring context starts. */
    val baseUri: String = "http://$LOOPBACK_HOST:${server.address.port}"

    init {
        server.createContext(ROOT_PATH) { exchange -> handle(exchange) }
        server.executor = executor
        server.start()
    }

    fun stop() {
        server.stop(STOP_DELAY_SECONDS)
        executor.shutdownNow()
    }

    private fun handle(exchange: HttpExchange) {
        val response =
            try {
                route(exchange)
            } catch (cause: Exception) {
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(cause.toString())
            }
        respond(exchange, response)
        exchange.close()
    }

    @Suppress("ReturnCount") // each branch is one routed IdP endpoint
    private fun route(exchange: HttpExchange): ResponseEntity<String> {
        val path = exchange.requestURI.path
        return when {
            exchange.requestMethod == GET_METHOD && path == AUTHORIZE_PATH -> idp.authorize(toServletRequest(exchange))
            exchange.requestMethod == POST_METHOD && path == TOKEN_PATH -> idp.token(toServletRequest(exchange))
            exchange.requestMethod == GET_METHOD && path == JWKS_PATH ->
                ResponseEntity
                    .ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(idp.jwks())
            else -> ResponseEntity.notFound().build()
        }
    }

    private fun toServletRequest(exchange: HttpExchange): MockHttpServletRequest {
        val request = MockHttpServletRequest(exchange.requestMethod, exchange.requestURI.toString())
        exchange.requestHeaders.forEach { (name, values) -> values.forEach { request.addHeader(name, it) } }
        addParametersFrom(request, exchange.requestURI.rawQuery)
        addParametersFrom(request, exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8))
        return request
    }

    /** Splits a raw query/form string into decoded parameters — the IdP reads every input via getParameter. */
    private fun addParametersFrom(
        request: MockHttpServletRequest,
        raw: String?,
    ) {
        if (raw.isNullOrEmpty()) return
        raw.split(AMPERSAND).forEach { pair ->
            val separator = pair.indexOf(EQUALS_SIGN)
            if (separator <= 0) return@forEach
            val name = pair.substring(0, separator)
            val value = pair.substring(separator + 1)
            request.addParameter(
                java.net.URLDecoder.decode(name, StandardCharsets.UTF_8),
                java.net.URLDecoder.decode(value, StandardCharsets.UTF_8),
            )
        }
    }

    private fun respond(
        exchange: HttpExchange,
        response: ResponseEntity<String>,
    ) {
        response.headers.location?.let { exchange.responseHeaders.set(HttpHeaders.LOCATION, it.toString()) }
        val body = response.body?.toByteArray(StandardCharsets.UTF_8) ?: ByteArray(0)
        exchange.responseHeaders.set(
            HttpHeaders.CONTENT_TYPE,
            response.headers.contentType?.toString() ?: MediaType.APPLICATION_JSON_VALUE,
        )
        exchange.sendResponseHeaders(
            response.statusCode.value(),
            if (body.isEmpty()) NO_BODY else body.size.toLong(),
        )
        if (body.isNotEmpty()) exchange.responseBody.use { stream -> stream.write(body) }
    }

    private companion object {
        const val LOOPBACK_HOST = "127.0.0.1"

        const val ROOT_PATH = "/"

        const val AUTHORIZE_PATH = "/mock-idp/authorize"

        const val TOKEN_PATH = "/mock-idp/token"

        const val JWKS_PATH = "/mock-idp/jwks"

        const val THREAD_NAME = "pdit-idp-loopback"

        const val BACKLOG = 0

        const val STOP_DELAY_SECONDS = 0

        const val NO_BODY = -1L

        const val GET_METHOD = "GET"

        const val POST_METHOD = "POST"

        const val AMPERSAND = "&"

        const val EQUALS_SIGN = '='
    }
}
