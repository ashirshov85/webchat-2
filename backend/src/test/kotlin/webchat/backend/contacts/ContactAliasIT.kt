package webchat.backend.contacts

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
 * T010 (tasks.md Phase 3, US1): the contact-alias slice of the 008a
 * contract (api-contract.md §1 №40 + §2, openapi.yaml 0.9.0,
 * FR-003, US1 AC2/AC3/AC4/AC5):
 *
 *  * №40 `PUT /contacts/{userId}/alias` — set/reset of the personal
 *    alias: the SERVER trims surrounding whitespace before storing (the
 *    stored value and every later projection carry the trimmed form),
 *    `null`/an omitted field resets («not set»), and a later write
 *    overwrites the earlier one (last-write-wins); Unicode is
 *    unrestricted (cyrillic/emoji);
 *  * validation: an empty string, a whitespace-only string and an alias
 *    longer than 64 characters AFTER trim are refused
 *    `400 invalid_alias` (`errors: {alias: [...]}`, problem+json) while
 *    the previously stored value SURVIVES (US1 AC5); the 64-character
 *    bound itself is valid;
 *  * `404 contact_not_found` (`errors: {userId: [...]}`) — the target
 *    is not a contact of the caller: both an existing user never added
 *    and an unknown userId;
 *  * privacy (FR-003, US1 AC2): the alias is the OWNER's personal name
 *    and rides ONLY his surfaces — №40/№20 `ContactView.alias`, the
 *    `peerAlias` fragments of №11/№12/№13, the №19 search result and
 *    the №28 group member fragment (`UserWithAlias`); the contact
 *    himself and third parties never receive the field on any surface;
 *    a contact without an alias keeps the field ABSENT everywhere
 *    (backward compatibility, US1 AC4);
 *  * lifecycle (api-contract.md §1 №40): the alias survives deletion of
 *    the chat (№14 — it lives on `user_contacts`, not on chats) and is
 *    deleted together with the contact (№22 — the row goes as a whole;
 *    a re-add starts from a clean slate);
 *  * flood: the per-user bucket `rl:user:alias:{userId}` (30/min)
 *    refuses the burst `429 flood_limit` + integral `Retry-After ≥ 1`
 *    and is strictly per-user — a different user writes right after
 *    the refusal undisturbed.
 *
 * NOTE (TDD, constitution VI): written BEFORE T013/T014 (and the
 * projection legs of T015–T017) land — until the №40 route exists every
 * PUT here answers 404/405 without the contract problem body, so the
 * positive legs fail (RED) by design; T026 acceptance is this class
 * green together with ProfileDisplayNameIT.
 */
@Suppress("TooManyFunctions", "LargeClass") // T010: one helper per contract operation, one method per US1 rule
class ContactAliasIT(
    @Autowired private val restTemplate: TestRestTemplate,
    @Autowired private val objectMapper: ObjectMapper,
) : MessagingTestSupport() {
    /** №40 happy path (set leg): 200 + the trimmed `ContactView` body; last write wins; null/omitted reset. */
    @Test
    fun `set contact alias trims stores resets and the last write wins`() {
        val (owner, peer) = messagingPair()
        assertThat(addContact(owner, peer.id).statusCode)
            .overridingErrorMessage("the fixture contact must be addable before the alias legs")
            .isEqualTo(HttpStatus.CREATED)

        // Backward-compat baseline: a fresh contact carries NO alias (US1 AC4).
        val baseline = contactOf(listContacts(owner), peer.id)
        assertThat(baseline.hasAlias())
            .overridingErrorMessage("a fresh contact must NOT carry alias in №20")
            .isFalse
        assertConformsToSchema(baseline, CONTACT_VIEW_SCHEMA)

        // Set with surrounding whitespace: the server trims BEFORE storing (FR-003).
        val set = setAlias(owner, peer.id, "  $ALIAS  ")
        assertThat(set.statusCode)
            .overridingErrorMessage(
                "№40 must accept a padded alias and answer 200, got <%s>: %s",
                set.statusCode,
                set.body,
            ).isEqualTo(HttpStatus.OK)
        val setView = objectMapper.readTree(set.body!!)
        assertThat(setView["user"]["id"].asText())
            .overridingErrorMessage("the №40 answer must describe the aliased contact, got <%s>", set.body)
            .isEqualTo(peer.id.toString())
        assertThat(aliasTextOf(setView))
            .overridingErrorMessage(
                "№40 must return the TRIMMED stored value, got <%s> in %s",
                aliasTextOf(setView),
                set.body,
            ).isEqualTo(ALIAS)
        assertThat(setView["blockedByMe"].asBoolean())
            .overridingErrorMessage("a fresh contact is not blocked, got <%s>", set.body)
            .isFalse
        assertThat(setView.has(CREATED_AT_FIELD))
            .overridingErrorMessage("the №40 answer must keep the ContactView.createdAt slot, got <%s>", set.body)
            .isTrue
        assertConformsToSchema(setView, CONTACT_VIEW_SCHEMA)

        assertThat(aliasTextOf(contactOf(listContacts(owner), peer.id)))
            .overridingErrorMessage("№20 must serve the stored alias")
            .isEqualTo(ALIAS)

        // Idempotency semantics: the latest write wins (api-contract.md §1 №40).
        val rewritten = setAlias(owner, peer.id, OTHER_ALIAS)
        assertThat(rewritten.statusCode)
            .overridingErrorMessage(
                "a repeated №40 of a new value must answer 200, got <%s>: %s",
                rewritten.statusCode,
                rewritten.body,
            ).isEqualTo(HttpStatus.OK)
        assertThat(aliasTextOf(objectMapper.readTree(rewritten.body!!))).isEqualTo(OTHER_ALIAS)
        assertThat(aliasTextOf(contactOf(listContacts(owner), peer.id)))
            .overridingErrorMessage("№20 must serve the LAST written alias")
            .isEqualTo(OTHER_ALIAS)

        // Unicode is unrestricted (US1 edge: cyrillic and emoji aliases).
        val unicode = setAlias(owner, peer.id, UNICODE_ALIAS)
        assertThat(unicode.statusCode)
            .overridingErrorMessage("№40 must accept a Unicode alias, got <%s>: %s", unicode.statusCode, unicode.body)
            .isEqualTo(HttpStatus.OK)
        assertThat(aliasTextOf(objectMapper.readTree(unicode.body!!))).isEqualTo(UNICODE_ALIAS)
    }

    /** №40 reset leg: explicit null AND the omitted field clear the alias («not set», US1 AC3). */
    @Test
    fun `null and the omitted field reset the contact alias`() {
        val (owner, peer) = messagingPair()
        assertThat(addContact(owner, peer.id).statusCode)
            .overridingErrorMessage("the fixture contact must be addable before the reset legs")
            .isEqualTo(HttpStatus.CREATED)
        assertThat(setAlias(owner, peer.id, ALIAS).statusCode)
            .overridingErrorMessage("the fixture alias must be storable before the reset legs")
            .isEqualTo(HttpStatus.OK)

        // Clear: explicit null resets to «not set» (NOT a blank string — that is a 400).
        val cleared = setAlias(owner, peer.id, null)
        assertThat(cleared.statusCode)
            .overridingErrorMessage(
                "№40 null must reset the alias with 200, got <%s>: %s",
                cleared.statusCode,
                cleared.body,
            ).isEqualTo(HttpStatus.OK)
        assertThat(aliasTextOf(objectMapper.readTree(cleared.body!!)))
            .overridingErrorMessage("a reset contact must NOT carry alias in the №40 answer")
            .isNull()

        // The omitted field carries the same reset semantics as null.
        val omitted = setAliasOmittingField(owner, peer.id)
        assertThat(omitted.statusCode)
            .overridingErrorMessage(
                "№40 without the field must reset with 200, got <%s>: %s",
                omitted.statusCode,
                omitted.body,
            ).isEqualTo(HttpStatus.OK)
        assertThat(aliasTextOf(objectMapper.readTree(omitted.body!!))).isNull()
        assertThat(aliasTextOf(contactOf(listContacts(owner), peer.id)))
            .overridingErrorMessage("a reset contact must NOT carry alias in №20")
            .isNull()
    }

    /** №40 validation: blank/oversized aliases are 400 `invalid_alias`, the stored value survives (US1 AC5). */
    @Test
    fun `blank and oversized aliases are refused 400 invalid_alias keeping the stored value`() {
        val (owner, peer) = messagingPair()
        assertThat(addContact(owner, peer.id).statusCode)
            .overridingErrorMessage("the fixture contact must be addable before the refusals")
            .isEqualTo(HttpStatus.CREATED)
        assertThat(setAlias(owner, peer.id, ALIAS).statusCode)
            .overridingErrorMessage("the fixture alias must be storable before the refusals")
            .isEqualTo(HttpStatus.OK)

        assertInvalidAlias(setAlias(owner, peer.id, ""), "an empty string")
        assertInvalidAlias(setAlias(owner, peer.id, BLANK_ALIAS), "a whitespace-only string")
        assertInvalidAlias(setAlias(owner, peer.id, OVERSIZED_ALIAS), "an alias longer than 64 characters")

        assertThat(aliasTextOf(contactOf(listContacts(owner), peer.id)))
            .overridingErrorMessage("a refused №40 must keep the previously stored alias (US1 AC5)")
            .isEqualTo(ALIAS)

        val boundary = setAlias(owner, peer.id, BOUNDARY_ALIAS)
        assertThat(boundary.statusCode)
            .overridingErrorMessage(
                "the 64-character bound itself must stay valid, got <%s>: %s",
                boundary.statusCode,
                boundary.body,
            ).isEqualTo(HttpStatus.OK)
        assertThat(aliasTextOf(objectMapper.readTree(boundary.body!!))).isEqualTo(BOUNDARY_ALIAS)
    }

    /** №40 membership gate: a target that is not a contact of the caller is `404 contact_not_found`. */
    @Test
    fun `alias for a non-contact target is refused 404 contact_not_found`() {
        val owner = messagingUser("renamer")
        val stranger = messagingUser("unrelated")

        assertContactNotFound(setAlias(owner, stranger.id, ALIAS), "an existing user who is not a contact")
        assertContactNotFound(setAlias(owner, UUID.randomUUID(), ALIAS), "an unknown user")

        assertThat(contactIds(listContacts(owner)))
            .overridingErrorMessage("a refused №40 must leave no contact rows behind")
            .isEmpty()
    }

    /**
     * FR-003/US1 AC2 propagation over the OWNER's surfaces: №20
     * (`ContactView.alias`) and the peer fragments of №11 (ensure),
     * №12 (list) and №13 (get) — `peerAlias` — plus the №19 search
     * result and the №28 group member fragment (`UserWithAlias`) all
     * carry the stored alias; absent before the alias is set (US1 AC4).
     */
    @Test
    fun `alias rides the owner contact chat search and group surfaces`() {
        val owner = messagingUser("bookkeep")
        val peer = messagingUser("bookpeer")
        val third = messagingUser("bookthird")
        assertThat(addContact(owner, peer.id).statusCode)
            .overridingErrorMessage("the alias fixture needs the peer among the owner's contacts")
            .isEqualTo(HttpStatus.CREATED)
        val chatId = ensureChatOk(owner, peer.id)

        assertThat(peerAliasTextOf(objectMapper.readTree(ensureChat(owner, peer.id).body!!)))
            .overridingErrorMessage("№11 peerAlias must be ABSENT while no alias is set (SC-007)")
            .isNull()
        assertThat(peerAliasTextOf(chatListItemOf(listChats(owner), chatId)))
            .overridingErrorMessage("№12 peerAlias must be ABSENT while no alias is set (SC-007)")
            .isNull()
        assertThat(peerAliasTextOf(objectMapper.readTree(getChat(owner, chatId).body!!)))
            .overridingErrorMessage("№13 peerAlias must be ABSENT while no alias is set (SC-007)")
            .isNull()

        assertThat(setAlias(owner, peer.id, ALIAS).statusCode)
            .overridingErrorMessage("the fixture alias must land before the surface legs")
            .isEqualTo(HttpStatus.OK)

        val ensured = ensureChat(owner, peer.id)
        assertThat(ensured.statusCode.is2xxSuccessful)
            .overridingErrorMessage("№11 re-ensure must succeed, got <%s>: %s", ensured.statusCode, ensured.body)
            .isTrue
        val ensuredView = objectMapper.readTree(ensured.body!!)
        assertThat(peerAliasTextOf(ensuredView))
            .overridingErrorMessage("№11 must carry the owner's peerAlias")
            .isEqualTo(ALIAS)
        assertConformsToSchema(ensuredView, CHAT_VIEW_SCHEMA)

        val listedItem = chatListItemOf(listChats(owner), chatId)
        assertThat(peerAliasTextOf(listedItem))
            .overridingErrorMessage("№12 must carry the owner's peerAlias")
            .isEqualTo(ALIAS)
        assertConformsToSchema(listedItem, CHAT_LIST_ITEM_SCHEMA)

        val fetchedView = objectMapper.readTree(getChat(owner, chatId).body!!)
        assertThat(peerAliasTextOf(fetchedView))
            .overridingErrorMessage("№13 must carry the owner's peerAlias")
            .isEqualTo(ALIAS)
        assertConformsToSchema(fetchedView, CHAT_VIEW_SCHEMA)

        // №19: the found user IS the caller's contact — the result carries the caller's alias.
        val found = foundUserOf(searchUsers(owner, peer.username))
        assertThat(aliasTextOf(found))
            .overridingErrorMessage("№19 must carry the caller's alias to a found contact")
            .isEqualTo(ALIAS)
        assertConformsToSchema(found, USER_WITH_ALIAS_SCHEMA)

        // №28: the group member fragment carries the caller's alias to the member.
        val groupChatId = chatIdOf(createGroupOk(owner, GROUP_TITLE, listOf(peer.id, third.id)))
        val member = memberUserOf(getGroup(owner, groupChatId), peer.id)
        assertThat(aliasTextOf(member))
            .overridingErrorMessage("№28 member fragment must carry the caller's alias")
            .isEqualTo(ALIAS)
        assertConformsToSchema(member, USER_WITH_ALIAS_SCHEMA)
    }

    /**
     * FR-003/US1 AC2 privacy: the alias is strictly personal — the
     * contact himself and third parties never receive the field. The
     * alias slots of THEIR views stay empty: the contact's own №13 peer
     * fragment (his peer is the owner — he set no alias), his №19
     * self-search (he is not his own contact) and the third party's №19
     * search plus №28 group view of the same aliased member.
     */
    @Test
    fun `alias never reaches the contact himself or third parties`() {
        val owner = messagingUser("hider")
        val peer = messagingUser("hidden")
        val third = messagingUser("watcher")
        assertThat(addContact(owner, peer.id).statusCode)
            .overridingErrorMessage("the privacy fixture needs the peer among the owner's contacts")
            .isEqualTo(HttpStatus.CREATED)
        val chatId = ensureChatOk(owner, peer.id)
        assertThat(setAlias(owner, peer.id, ALIAS).statusCode)
            .overridingErrorMessage("the privacy fixture needs a stored alias")
            .isEqualTo(HttpStatus.OK)
        val groupChatId = chatIdOf(createGroupOk(owner, GROUP_TITLE, listOf(peer.id, third.id)))

        // The contact himself: his №13 view of the dialog — his peer is
        // the owner and HE has set no alias toward him.
        val peerChatView = objectMapper.readTree(getChat(peer, chatId).body!!)
        assertThat(peerChatView["peer"]["id"].asText())
            .overridingErrorMessage("the fixture dialog must serve the owner as the peer of the contact")
            .isEqualTo(owner.id.toString())
        assertThat(peerAliasTextOf(peerChatView))
            .overridingErrorMessage("the contact's own №13 must not carry any alias he has not set")
            .isNull()
        assertConformsToSchema(peerChatView, CHAT_VIEW_SCHEMA)

        // The contact's self-search: the alias slot of the found user is
        // the SEARCHER's alias — he is not his own contact.
        val selfFound = foundUserOf(searchUsers(peer, peer.username))
        assertThat(aliasTextOf(selfFound))
            .overridingErrorMessage("the contact's own №19 self-search must NOT carry the owner's alias")
            .isNull()
        assertConformsToSchema(selfFound, USER_WITH_ALIAS_SCHEMA)

        // Third parties: №19 search and the №28 group view of the same member.
        val thirdFound = foundUserOf(searchUsers(third, peer.username))
        assertThat(aliasTextOf(thirdFound))
            .overridingErrorMessage("a third party's №19 must NOT carry the owner's alias")
            .isNull()
        assertConformsToSchema(thirdFound, USER_WITH_ALIAS_SCHEMA)

        val thirdMemberView = memberUserOf(getGroup(third, groupChatId), peer.id)
        assertThat(aliasTextOf(thirdMemberView))
            .overridingErrorMessage("a third party's №28 view must NOT carry the owner's alias")
            .isNull()
        assertConformsToSchema(thirdMemberView, USER_WITH_ALIAS_SCHEMA)

        val peerMemberView = memberUserOf(getGroup(peer, groupChatId), peer.id)
        assertThat(aliasTextOf(peerMemberView))
            .overridingErrorMessage("the contact's own №28 fragment must NOT carry the owner's alias")
            .isNull()
        assertConformsToSchema(peerMemberView, USER_WITH_ALIAS_SCHEMA)
    }

    /**
     * api-contract.md §1 №40 lifecycle: the alias survives deletion of
     * the chat (№14 — it lives on `user_contacts`, not on chats) and is
     * deleted together with the contact (№22 — the row goes as a whole;
     * a re-add starts from a clean slate, data-model.md §1.2).
     */
    @Test
    fun `alias survives chat deletion and dies with the contact row`() {
        val (owner, peer) = messagingPair()
        assertThat(addContact(owner, peer.id).statusCode)
            .overridingErrorMessage("the lifecycle fixture needs the peer among the owner's contacts")
            .isEqualTo(HttpStatus.CREATED)
        val chatId = ensureChatOk(owner, peer.id)
        sendMessageOk(owner, chatId, "history line that must not affect the alias row")
        assertThat(setAlias(owner, peer.id, ALIAS).statusCode)
            .overridingErrorMessage("the lifecycle fixture needs a stored alias")
            .isEqualTo(HttpStatus.OK)

        // №14: the chat is hidden for the owner — the contact row and its alias stay.
        assertThat(deleteChat(owner, chatId).statusCode)
            .overridingErrorMessage("№14 chat deletion must answer 204")
            .isEqualTo(HttpStatus.NO_CONTENT)
        val survived = contactOf(listContacts(owner), peer.id)
        assertThat(aliasTextOf(survived))
            .overridingErrorMessage("the alias must SURVIVE the chat deletion (№40 lifecycle)")
            .isEqualTo(ALIAS)
        assertConformsToSchema(survived, CONTACT_VIEW_SCHEMA)

        // №22: the contact deletion removes the whole row WITH the alias.
        assertThat(deleteContact(owner, peer.id).statusCode)
            .overridingErrorMessage("№22 contact deletion must answer 204")
            .isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(contactIds(listContacts(owner)))
            .overridingErrorMessage("the deleted contact must be gone from the list")
            .isEmpty()
        assertContactNotFound(setAlias(owner, peer.id, ALIAS), "a deleted contact")

        // A re-add starts from a clean slate — the alias must not resurrect.
        assertThat(addContact(owner, peer.id).statusCode)
            .overridingErrorMessage("the re-add after the deletion must create the contact again")
            .isEqualTo(HttpStatus.CREATED)
        assertThat(aliasTextOf(contactOf(listContacts(owner), peer.id)))
            .overridingErrorMessage("a re-added contact must come back WITHOUT the old alias (clean slate)")
            .isNull()
    }

    /**
     * №40 flood (research.md C2): the per-user 30/min bucket
     * `rl:user:alias:{userId}`. The drain burst must spend the FULL
     * allowance before any 429; like [ContactsIT][webchat.backend.contacts.ContactsIT],
     * the greedy 30/60s drip re-grants tokens mid-burst, so the first
     * refusal lands at attempt 31+ within `30 + elapsed/2 + 1` — never
     * at a fixed index. The refusal is the contract 429: problem+json
     * with `errors.alias=[flood_limit]` and an integral `Retry-After`
     * of ≥1s; the bucket is strictly per-user, so a DIFFERENT user
     * writes right after undisturbed.
     */
    @Test
    fun `alias writes past the thirty-per-minute allowance are refused 429 flood_limit with Retry-After`() {
        val flooder = messagingUser("alias-flooder")
        val target = messagingUser("alias-target")
        val bystander = messagingUser("alias-bystander")
        val bystanderPeer = messagingUser("alias-bystander-peer")
        assertThat(addContact(flooder, target.id).statusCode)
            .overridingErrorMessage("the flood fixture needs a contact of the flooder")
            .isEqualTo(HttpStatus.CREATED)
        assertThat(addContact(bystander, bystanderPeer.id).statusCode)
            .overridingErrorMessage("the flood fixture needs a contact of the bystander")
            .isEqualTo(HttpStatus.CREATED)

        var accepted = 0
        var rejection: ResponseEntity<String>? = null
        val startedAt = System.nanoTime()
        var attempt = 0
        while (rejection == null && attempt < FLOOD_BURST_ATTEMPT_CAP) {
            attempt += 1
            val response = setAlias(flooder, target.id, "flood-alias-$attempt")
            if (response.statusCode == HttpStatus.OK) {
                accepted += 1
            } else {
                rejection = response
            }
        }
        val elapsedSeconds = (System.nanoTime() - startedAt) / NANOS_PER_SECOND
        assertThat(rejection)
            .overridingErrorMessage(
                "the 30-writes-per-minute №40 bucket must refuse the burst of %d alias writes, " +
                    "but every attempt answered 200 — the flood limit is not enforced on №40",
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
        assertThat(setAlias(bystander, bystanderPeer.id, ALIAS).statusCode)
            .overridingErrorMessage("the №40 flood bucket is per-user — a different user must write undisturbed")
            .isEqualTo(HttpStatus.OK)
    }

    // ------------------------------------------------------------------
    // Contract №40/№20/№21/№22/№19/№27/№28 call helpers (raw responses).
    // ------------------------------------------------------------------

    /** Contract №40 `PUT /api/v1/contacts/{userId}/alias` — `alias: value|null`. */
    private fun setAlias(
        user: MessagingUser,
        contactUserId: UUID,
        alias: String?,
    ): ResponseEntity<String> = putJson(aliasPath(contactUserId), mapOf(ALIAS_FIELD to alias), user)

    /** Contract №40 with the field OMITTED entirely — the «absence = reset» leg. */
    private fun setAliasOmittingField(
        user: MessagingUser,
        contactUserId: UUID,
    ): ResponseEntity<String> = putJson(aliasPath(contactUserId), emptyMap(), user)

    private fun aliasPath(contactUserId: UUID): String = "$CONTACTS_PATH/$contactUserId/alias"

    /** Contract №20 `GET /api/v1/contacts?sort=` — raw response. */
    private fun listContacts(user: MessagingUser): ResponseEntity<String> =
        exchangeAuthed(HttpMethod.GET, CONTACTS_PATH, user)

    /** Contract №21 `POST /api/v1/contacts` — raw response. */
    private fun addContact(
        user: MessagingUser,
        userId: UUID,
    ): ResponseEntity<String> = postJson(CONTACTS_PATH, mapOf(USER_ID_FIELD to userId.toString()), user)

    /** Contract №22 `DELETE /api/v1/contacts/{userId}` — raw response. */
    private fun deleteContact(
        user: MessagingUser,
        userId: UUID,
    ): ResponseEntity<String> = exchangeAuthed(HttpMethod.DELETE, "$CONTACTS_PATH/$userId", user)

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

    // ------------------------------------------------------------------
    // Body navigation and assertions.
    // ------------------------------------------------------------------

    private fun contactIds(response: ResponseEntity<String>): List<String> =
        objectMapper.readTree(response.body!!)["contacts"].map { it["user"]["id"].asText() }

    private fun contactOf(
        response: ResponseEntity<String>,
        userId: UUID,
    ): JsonNode =
        objectMapper.readTree(response.body!!)["contacts"].firstOrNull {
            it["user"]["id"].asText() == userId.toString()
        } ?: error("the №20 list must carry the contact of user $userId in ${response.body}")

    private fun chatListItemOf(
        response: ResponseEntity<String>,
        chatId: UUID,
    ): JsonNode =
        objectMapper.readTree(response.body!!)["chats"].firstOrNull { it["chatId"].asText() == chatId.toString() }
            ?: error("the №12 list must carry the item of chat $chatId in ${response.body}")

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

    private fun JsonNode.hasAlias(): Boolean = has(ALIAS_FIELD) && !this[ALIAS_FIELD].isNull

    private fun aliasTextOf(node: JsonNode): String? = if (node.hasAlias()) node[ALIAS_FIELD].asText() else null

    private fun peerAliasTextOf(node: JsonNode): String? =
        if (node.has(PEER_ALIAS_FIELD) && !node[PEER_ALIAS_FIELD].isNull) node[PEER_ALIAS_FIELD].asText() else null

    /** The №40 400 of api-contract.md: problem+json with `errors.alias=[invalid_alias]`. */
    private fun assertInvalidAlias(
        response: ResponseEntity<String>,
        case: String,
    ) {
        assertThat(response.statusCode)
            .overridingErrorMessage("%s must be refused 400, got <%s>: %s", case, response.statusCode, response.body)
            .isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.headers.contentType?.toString())
            .overridingErrorMessage("the №40 refusal must be RFC 9457 application/problem+json")
            .contains(PROBLEM_JSON_MEDIA_TYPE)
        val codes =
            objectMapper
                .readTree(response.body!!)["errors"]
                ?.get(ALIAS_FIELD)
                ?.map { it.asText() }
                .orEmpty()
        assertThat(codes)
            .overridingErrorMessage(
                "the problem must carry errors.%s=[%s], got <%s> in %s",
                ALIAS_FIELD,
                INVALID_ALIAS,
                codes,
                response.body,
            ).containsExactly(INVALID_ALIAS)
    }

    /** The №40 404 of api-contract.md: problem+json with `errors.userId=[contact_not_found]`. */
    private fun assertContactNotFound(
        response: ResponseEntity<String>,
        case: String,
    ) {
        assertThat(response.statusCode)
            .overridingErrorMessage("%s must be refused 404, got <%s>: %s", case, response.statusCode, response.body)
            .isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(response.headers.contentType?.toString())
            .overridingErrorMessage("the №40 refusal must be RFC 9457 application/problem+json")
            .contains(PROBLEM_JSON_MEDIA_TYPE)
        val codes =
            objectMapper
                .readTree(response.body!!)["errors"]
                ?.get(USER_ID_FIELD)
                ?.map { it.asText() }
                .orEmpty()
        assertThat(codes)
            .overridingErrorMessage(
                "the problem must carry errors.%s=[%s], got <%s> in %s",
                USER_ID_FIELD,
                CONTACT_NOT_FOUND,
                codes,
                response.body,
            ).containsExactly(CONTACT_NOT_FOUND)
    }

    /** №40 429 of api-contract.md: `errors.alias=[flood_limit]` + integral `Retry-After` ≥ 1s. */
    private fun assertFloodRejection(rejection: ResponseEntity<String>) {
        assertThat(rejection.statusCode)
            .overridingErrorMessage(
                "the №40 flood refusal must be 429, got <%s>: %s",
                rejection.statusCode,
                rejection.body,
            ).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
        assertThat(rejection.headers.contentType?.toString())
            .overridingErrorMessage("the №40 flood refusal must be RFC 9457 application/problem+json")
            .contains(PROBLEM_JSON_MEDIA_TYPE)
        val retryAfterHeader = rejection.headers.getFirst(HttpHeaders.RETRY_AFTER)
        val retryAfterSeconds = retryAfterHeader?.trim()?.toLongOrNull()
        assertThat(retryAfterSeconds)
            .overridingErrorMessage(
                "Retry-After must be integral seconds ≥ 1 (openapi №40), got <%s>",
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
                ?.get(ALIAS_FIELD)
                ?.map { it.asText() }
                .orEmpty()
        assertThat(codes)
            .overridingErrorMessage(
                "the problem must carry errors.%s=[%s], got <%s> in %s",
                ALIAS_FIELD,
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
        const val CONTACTS_PATH = "/api/v1/contacts"
        const val SEARCH_PATH = "/api/v1/users/search"
        const val GROUPS_PATH = "/api/v1/groups"

        const val ALIAS_FIELD = "alias"
        const val PEER_ALIAS_FIELD = "peerAlias"
        const val CREATED_AT_FIELD = "createdAt"
        const val QUERY_FIELD = "query"
        const val USER_ID_FIELD = "userId"
        const val TITLE_FIELD = "title"
        const val MEMBER_USER_IDS_FIELD = "memberUserIds"

        const val INVALID_ALIAS = "invalid_alias"
        const val CONTACT_NOT_FOUND = "contact_not_found"
        const val FLOOD_LIMIT = "flood_limit"
        const val PROBLEM_JSON_MEDIA_TYPE = "application/problem+json"

        const val CONTACT_VIEW_SCHEMA = "ContactView"
        const val CHAT_VIEW_SCHEMA = "ChatView"
        const val CHAT_LIST_ITEM_SCHEMA = "ChatListItem"
        const val USER_WITH_ALIAS_SCHEMA = "UserWithAlias"
        const val REF_PREFIX = "#"

        const val ALIAS = "Маша"
        const val OTHER_ALIAS = "Машуня"
        const val UNICODE_ALIAS = "🙂Маша🙂"
        const val BLANK_ALIAS = " \t  "
        const val ALIAS_MAX_LENGTH = 64
        val BOUNDARY_ALIAS: String = "м".repeat(ALIAS_MAX_LENGTH)
        val OVERSIZED_ALIAS: String = "м".repeat(ALIAS_MAX_LENGTH + 1)
        const val GROUP_TITLE = "008a contact-alias surfaces"

        /** api-contract.md §1 №40: 30 alias writes per minute per user. */
        const val FLOOD_WINDOW = 30
        const val FLOOD_BURST_ATTEMPT_CAP = 40
        const val FLOOD_DRIP_SLACK = 1L
        const val RETRY_AFTER_FLOOR_SECONDS = 1L
        const val RETRY_AFTER_CEILING_SECONDS = 60L
        const val NANOS_PER_SECOND = 1_000_000_000L

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
                            "the T010 conformance check must read the live public contract",
                    )
            return contract.toFile().inputStream().use { input ->
                val yamlRoot: Any? = Yaml().load(input)
                contractJson.valueToTree<JsonNode>(yamlRoot)
            }
        }
    }
}
