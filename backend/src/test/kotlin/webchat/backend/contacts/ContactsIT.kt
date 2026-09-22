package webchat.backend.contacts

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
import org.springframework.web.util.UriComponentsBuilder
import webchat.backend.chats.MessagingTestSupport
import java.util.Optional
import java.util.UUID

/**
 * T047 (tasks.md Phase 7, US5): the contacts/search slice of the contract
 * §3 (api-contract.md №19–№22, openapi.yaml 0.4.0, FR-015..FR-017):
 *
 *  * №19 `GET /users/search` — EXACT match of the full email OR the full
 *    login, case-insensitive, routed by the `@` rule: a query containing
 *    `@` compares `lower(email)` only, everything else compares
 *    `lower(username)` (a username cannot contain `@`, rule 002) — an
 *    email-shaped miss never falls back to a username match; 0 results is a
 *    clean `200 {users: []}` (edge); a missing/empty/>254-char `query` is
 *    refused `400 query_missing` (`errors: {query: [...]}`), the 254-char
 *    boundary itself stays valid;
 *  * №21 `POST /contacts` — `201` on create, `200` + the SAME row (no
 *    duplicate) on re-add, `422 self_forbidden` for self, `404
 *    user_not_found` for an unknown user;
 *  * №22 `DELETE /contacts/{userId}` — idempotent `204`; the dialog and its
 *    history of the pair are NOT contact-derived and survive the deletion
 *    for BOTH participants (FR-017);
 *  * №20 `GET /contacts` — server-side case-insensitive alphabetical
 *    sorting by `sort=login|email` (default `login`, FR-015); any other
 *    `sort` value is refused `400 invalid_sort`. The fixtures deliberately
 *    pin mixed-case usernames and emails whose case-insensitive order is
 *    the REVERSE of the byte order (`Mike`/`Zulu` before `alpha`, `Oscar`
 *    before `bravo`/`november`), so a naive `ORDER BY` fails both sorts.
 *
 * NOTE (TDD, constitution VI): written BEFORE T050–T053 — until the
 * contacts endpoints land, every method here fails (RED) by design.
 */
class ContactsIT(
    @Autowired private val restTemplate: TestRestTemplate,
    @Autowired private val objectMapper: ObjectMapper,
) : MessagingTestSupport() {
    /**
     * FR-016/№19: the `@`-rule routes the comparison — the exact email
     * (with `@`) and the exact login (without) both find the user from any
     * letter case, and an email-shaped query never falls back to a username
     * match.
     */
    @Test
    fun `exact search follows the @ rule and matches email and username case-insensitively`() {
        val dave = messagingUser("dave")
        val erin = messagingUser("erin")

        val byEmail = searchUsers(dave, dave.email)
        assertThat(byEmail.statusCode)
            .overridingErrorMessage(
                "search by the exact email must return 200, got <%s>: %s",
                byEmail.statusCode,
                byEmail.body,
            ).isEqualTo(HttpStatus.OK)
        assertThat(userIds(byEmail))
            .overridingErrorMessage(
                "search by the exact email must find exactly the addressee, got <%s> in %s",
                userIds(byEmail),
                byEmail.body,
            ).containsExactly(dave.id.toString())
        assertThat(objectMapper.readTree(byEmail.body)["users"][0]["username"].asText()).isEqualTo(dave.username)

        assertThat(userIds(searchUsers(erin, dave.email.uppercase())))
            .overridingErrorMessage(
                "search by an uppercased email must still find the addressee (lower(email) comparison)",
            ).containsExactly(dave.id.toString())

        assertThat(userIds(searchUsers(dave, dave.username)))
            .overridingErrorMessage("search by the exact login (no @) must find the user (lower(username) comparison)")
            .containsExactly(dave.id.toString())

        assertThat(userIds(searchUsers(dave, dave.username.uppercase())))
            .overridingErrorMessage(
                "search by an uppercased login must still find the user (lower(username) comparison)",
            ).containsExactly(dave.id.toString())

        // The query carries '@', so it is compared against emails ONLY —
        // the username match is not a fallback (№19/@-rule).
        assertThat(userIds(searchUsers(dave, "${dave.username}@nomatch.example")))
            .overridingErrorMessage(
                "an email-shaped query with no email match must stay empty, not fall back to usernames",
            ).isEmpty()
    }

    /** FR-016 edge: no match is a clean empty page — `200`, `users: []`, no error. */
    @Test
    fun `search without a match returns an empty result without errors`() {
        val wendy = messagingUser("wendy")

        val missingEmail = searchUsers(wendy, "ghost-${UUID.randomUUID()}@example.com")
        assertThat(missingEmail.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(missingEmail.headers.contentType?.toString())
            .overridingErrorMessage("a clean search miss must still answer application/json")
            .contains("application/json")
        assertThat(userIds(missingEmail)).isEmpty()

        assertThat(userIds(searchUsers(wendy, "ghost-${UUID.randomUUID().toString().substring(0, 8)}")))
            .overridingErrorMessage("a username search miss must answer an empty users array")
            .isEmpty()
    }

    /**
     * №19: `query` is required — missing, empty and longer than 254
     * characters are refused `400 query_missing`; the 254-character bound
     * itself is a valid (miss) query.
     */
    @Test
    fun `search rejects missing empty and oversized query with 400 query_missing`() {
        val pam = messagingUser("pam")

        assertQueryMissing(searchUsers(pam, query = null), "a missing query parameter")
        assertQueryMissing(searchUsers(pam, query = ""), "an empty query")
        assertQueryMissing(searchUsers(pam, "a".repeat(QUERY_MAX_LENGTH + 1)), "a query longer than 254 characters")

        val boundary = searchUsers(pam, "a".repeat(QUERY_MAX_LENGTH))
        assertThat(boundary.statusCode)
            .overridingErrorMessage(
                "the 254-character query bound must stay valid, got <%s>: %s",
                boundary.statusCode,
                boundary.body,
            ).isEqualTo(HttpStatus.OK)
        assertThat(userIds(boundary)).isEmpty()
    }

    /** №21/FR-016: adding oneself is refused `422 self_forbidden` and leaves no partial state. */
    @Test
    fun `adding self as a contact is refused with 422 self_forbidden`() {
        val alice = messagingUser("selfish")

        val refusal = addContact(alice, alice.id)
        assertThat(refusal.statusCode)
            .overridingErrorMessage("self-add must be refused 422, got <%s>: %s", refusal.statusCode, refusal.body)
            .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
        assertProblem(refusal, USER_ID_FIELD, SELF_FORBIDDEN)

        assertThat(contactIds(listContacts(alice)))
            .overridingErrorMessage("a refused self-add must leave the contact list empty")
            .isEmpty()
    }

    /** №21: an unknown target user is `404 user_not_found`, not a silent success. */
    @Test
    fun `adding an unknown user answers 404 user_not_found`() {
        val alice = messagingUser("seeker")

        val missing = addContact(alice, UUID.randomUUID())
        assertThat(missing.statusCode)
            .overridingErrorMessage(
                "adding an unknown user must be refused 404, got <%s>: %s",
                missing.statusCode,
                missing.body,
            ).isEqualTo(HttpStatus.NOT_FOUND)
        assertProblem(missing, USER_FIELD, USER_NOT_FOUND)
    }

    /**
     * №21 edge: the second add of the same user returns `200` with the SAME
     * stored `ContactView` — no second row appears in №20.
     */
    @Test
    fun `re-adding an existing contact returns 200 without a duplicate`() {
        val (alice, bob) = messagingPair()

        val first = addContact(alice, bob.id)
        assertThat(first.statusCode)
            .overridingErrorMessage(
                "the first add must create the contact (201), got <%s>: %s",
                first.statusCode,
                first.body,
            ).isEqualTo(HttpStatus.CREATED)
        val firstView = objectMapper.readTree(first.body)
        assertThat(firstView["user"]["id"].asText()).isEqualTo(bob.id.toString())

        val second = addContact(alice, bob.id)
        assertThat(second.statusCode)
            .overridingErrorMessage(
                "the repeated add must return the existing contact (200), got <%s>: %s",
                second.statusCode,
                second.body,
            ).isEqualTo(HttpStatus.OK)
        val secondView = objectMapper.readTree(second.body)
        assertThat(secondView["user"]["id"].asText()).isEqualTo(bob.id.toString())
        assertThat(secondView["createdAt"].asText())
            .overridingErrorMessage("the repeated add must return the SAME stored row (identical createdAt)")
            .isEqualTo(firstView["createdAt"].asText())

        assertThat(contactIds(listContacts(alice)))
            .overridingErrorMessage("after a repeated add the list must hold exactly one entry for the peer")
            .containsExactly(bob.id.toString())
    }

    /**
     * FR-017/№22: deleting a contact is idempotent (`204` again on repeat)
     * and independent of the dialog — chat and history survive for BOTH
     * participants, the contact list becomes empty.
     */
    @Test
    fun `deleting a contact keeps the chat and history of the pair`() {
        val (alice, bob) = messagingPair()
        val chatId = ensureChatOk(alice, bob.id)
        val firstText = "first line written before any contact bookkeeping"
        val secondText = "second line that must survive the contact deletion"
        sendMessageOk(alice, chatId, firstText)
        sendMessageOk(bob, chatId, secondText)

        assertThat(addContact(alice, bob.id).statusCode).isEqualTo(HttpStatus.CREATED)

        assertThat(deleteContact(alice, bob.id).statusCode)
            .overridingErrorMessage("contact deletion must answer 204")
            .isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(deleteContact(alice, bob.id).statusCode)
            .overridingErrorMessage("a repeated contact deletion must stay idempotent (204)")
            .isEqualTo(HttpStatus.NO_CONTENT)

        assertThat(contactIds(listContacts(alice)))
            .overridingErrorMessage("the deleted contact must be gone from the list")
            .isEmpty()

        assertThat(getChat(alice, chatId).statusCode)
            .overridingErrorMessage("the dialog must remain after the contact deletion (FR-017)")
            .isEqualTo(HttpStatus.OK)
        assertThat(historyTexts(alice, chatId))
            .overridingErrorMessage("the deleting user's history must be intact (FR-017)")
            .containsExactly(secondText, firstText)
        assertThat(historyTexts(bob, chatId))
            .overridingErrorMessage("the peer's history must be untouched by the contact deletion (FR-017)")
            .containsExactly(secondText, firstText)
    }

    /**
     * FR-015/№20: server-side case-insensitive alphabetical sorting —
     * default and `sort=login` by `lower(username)` (`alpha` < `mike` <
     * `zulu` — the byte order would put `Mike`/`Zulu` before `alpha`),
     * `sort=email` by `lower(email)` (`bravo` < `november` < `oscar` —
     * the byte order would put `Oscar` first).
     */
    @Test
    fun `contacts sort case-insensitively by login and by email`() {
        val owner = messagingUser("sorter")
        val suffix = UUID.randomUUID().toString().substring(0, 8)
        val zulu = messagingUser("Zulu", "bravo-$suffix@example.com")
        val alpha = messagingUser("alpha", "Oscar-$suffix@example.com")
        val mike = messagingUser("Mike", "november-$suffix@example.com")
        listOf(zulu, alpha, mike).forEach { peer ->
            assertThat(addContact(owner, peer.id).statusCode)
                .overridingErrorMessage("sort fixture contact add must succeed")
                .isEqualTo(HttpStatus.CREATED)
        }

        val byLoginDefault = contactIds(listContacts(owner))
        assertThat(byLoginDefault)
            .overridingErrorMessage("default sort must be sort=login (case-insensitive lower(username))")
            .containsExactly(alpha.id.toString(), mike.id.toString(), zulu.id.toString())

        val byLogin = contactIds(listContacts(owner, "login"))
        assertThat(byLogin)
            .overridingErrorMessage("sort=login must order by lower(username): alpha < mike < zulu, got <%s>", byLogin)
            .containsExactly(alpha.id.toString(), mike.id.toString(), zulu.id.toString())

        val byEmail = contactIds(listContacts(owner, "email"))
        assertThat(byEmail)
            .overridingErrorMessage(
                "sort=email must order by lower(email): bravo < november < oscar, got <%s>",
                byEmail,
            ).containsExactly(zulu.id.toString(), mike.id.toString(), alpha.id.toString())
    }

    /** №20: only `login` and `email` are valid sort values — anything else is `400 invalid_sort`. */
    @Test
    fun `contacts list rejects an unknown sort value with 400 invalid_sort`() {
        val owner = messagingUser("catalog")

        val refusal = listContacts(owner, "username")
        assertThat(refusal.statusCode)
            .overridingErrorMessage(
                "an unknown sort value must be refused 400, got <%s>: %s",
                refusal.statusCode,
                refusal.body,
            ).isEqualTo(HttpStatus.BAD_REQUEST)
        assertProblem(refusal, SORT_FIELD, INVALID_SORT)
    }

    /** Contract №19 `GET /api/v1/users/search?query=` — raw response (query omitted when null). */
    private fun searchUsers(
        user: MessagingUser,
        query: String?,
    ): ResponseEntity<String> {
        val path =
            UriComponentsBuilder
                .fromPath(SEARCH_PATH)
                .queryParamIfPresent(QUERY_FIELD, Optional.ofNullable(query))
                .build()
                .toUriString()
        return exchange(user, HttpMethod.GET, path)
    }

    /** Contract №20 `GET /api/v1/contacts?sort=login|email` — raw response. */
    private fun listContacts(
        user: MessagingUser,
        sort: String? = null,
    ): ResponseEntity<String> {
        val path =
            UriComponentsBuilder
                .fromPath(CONTACTS_PATH)
                .queryParamIfPresent(SORT_FIELD, Optional.ofNullable(sort))
                .build()
                .toUriString()
        return exchange(user, HttpMethod.GET, path)
    }

    /** Contract №21 `POST /api/v1/contacts` — raw response. */
    private fun addContact(
        user: MessagingUser,
        userId: UUID,
    ): ResponseEntity<String> {
        val headers =
            HttpHeaders().apply {
                contentType = MediaType.APPLICATION_JSON
                setBearerAuth(user.accessToken)
            }
        val body = mapOf(USER_ID_FIELD to userId.toString())
        return restTemplate.postForEntity(CONTACTS_PATH, HttpEntity(body, headers), String::class.java)
    }

    /** Contract №22 `DELETE /api/v1/contacts/{userId}` — raw response. */
    private fun deleteContact(
        user: MessagingUser,
        userId: UUID,
    ): ResponseEntity<String> = exchange(user, HttpMethod.DELETE, "$CONTACTS_PATH/$userId")

    private fun exchange(
        user: MessagingUser,
        method: HttpMethod,
        path: String,
    ): ResponseEntity<String> =
        restTemplate.exchange(
            path,
            method,
            HttpEntity<Void>(null, HttpHeaders().apply { setBearerAuth(user.accessToken) }),
            String::class.java,
        )

    private fun userIds(response: ResponseEntity<String>): List<String> =
        objectMapper.readTree(response.body)["users"].map { it["id"].asText() }

    private fun contactIds(response: ResponseEntity<String>): List<String> =
        objectMapper.readTree(response.body)["contacts"].map { it["user"]["id"].asText() }

    private fun historyTexts(
        user: MessagingUser,
        chatId: UUID,
    ): List<String> = objectMapper.readTree(listMessages(user, chatId).body)["messages"].map { it["text"].asText() }

    private fun assertQueryMissing(
        response: ResponseEntity<String>,
        case: String,
    ) {
        assertThat(response.statusCode)
            .overridingErrorMessage("%s must be refused 400, got <%s>: %s", case, response.statusCode, response.body)
            .isEqualTo(HttpStatus.BAD_REQUEST)
        assertProblem(response, QUERY_FIELD, QUERY_MISSING)
    }

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

    private companion object {
        const val PROBLEM_JSON_MEDIA_TYPE = "application/problem+json"
        const val SEARCH_PATH = "/api/v1/users/search"
        const val CONTACTS_PATH = "/api/v1/contacts"
        const val QUERY_FIELD = "query"
        const val USER_FIELD = "user"
        const val USER_ID_FIELD = "userId"
        const val SORT_FIELD = "sort"
        const val QUERY_MISSING = "query_missing"
        const val SELF_FORBIDDEN = "self_forbidden"
        const val USER_NOT_FOUND = "user_not_found"
        const val INVALID_SORT = "invalid_sort"
        const val QUERY_MAX_LENGTH = 254
    }
}
