package webchat.backend.conferences

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

/**
 * T065 (tasks.md Phase 8) — the RUNTIME legs of the security audit
 * (constitution V): the «проверка без Bearer» boundary of the 009
 * feature. The audit's static legs are code-review facts this class
 * documents and pins from the outside:
 *
 *  * **secrets stay env-only** — `conferences.livekit.api-key/secret`
 *    bind `${LIVEKIT_API_KEY}`/`${LIVEKIT_API_SECRET}` (the dev
 *    defaults are the PUBLIC `--dev` placeholder pair of
 *    deploy/local, a documented non-secret; prod overlays keep the
 *    `livekit-keys` Secret out of the repo — deploy/k8s), and the
 *    nonce ES256 keys ride the SAME `${AUTH_JWT_KEYS}`/
 *    `${AUTH_JWT_ACTIVE_KID}` env-only binding as the access tokens
 *    (application.yml: no default, fail-fast) — [GuestInviteTokens]
 *    introduces no second key store;
 *  * **no PII in the logs** — the webhook intake NEVER logs the
 *    payload or a participant identity (only the bare event TYPE,
 *    LiveKitWebhookController §5 «Приватность»), and every other
 *    `conferences` log site carries opaque UUIDs at most (the
 *    JdbcParticipantRepository convention);
 *  * **below, the testable boundary** — a caller with NO Bearer (the
 *    guest of FR-009 carries no token at all) must be refused by the
 *    uniform 401 entry point on EVERY chat API and on the whole
 *    member/moderation conferences surface №44–№53 — the chain
 *    rejects before any controller logic, so nothing is written and
 *    no problem detail beyond the static «Not authenticated» body is
 *    disclosed (no path echo, no existence oracle: a caller without
 *    a token cannot distinguish chats/conferences that exist from
 *    random UUIDs);
 *  * **the ONLY Bearer-less surface of the feature stays the public
 *    guest pair** — №54/№55 answer their contract verdicts (not the
 *    boundary 401) with the minimum-disclosure shapes of T046: the
 *    forged nonce folds into the bare `{valid:false}` / the uniform
 *    `404 invite_invalid` — no conference details, no forgery
 *    causes (the static review of GuestJoinController/
 *    ConferencesExceptionHandler confirms no endpoint echoes the
 *    submitted nonce or name; the T048 IT pins the full ladder).
 *
 * No media-plane leg runs here (the boundary rejects before any
 * service), so the class needs no context-separating knob — the
 * shared cached context of the conferences ITs serves as-is.
 */
class ConferenceSecurityAuditIT : ConferencesTestSupport() {
    @Autowired
    private lateinit var auditRestTemplate: TestRestTemplate

    /**
     * Constitution V / T065: EVERY chat API refuses a Bearer-less
     * caller with the ONE uniform 401 — the guest of a conference
     * link (FR-009: no account, no token) must not reach chats,
     * messages, contacts, groups or the profile surface. The verdicts
     * are byte-identical across endpoints (the static entry-point
     * body): nothing endpoint-specific — no path, no identifiers, no
     * existence oracle — leaks through the refusal.
     */
    @Test
    fun `every chat api refuses a bearer-less caller with the one uniform 401`() {
        val (owner, peer) = messagingPair()
        val chatId = ensureChatOk(owner, peer.id)
        val randomChatId = UUID_RANDOM

        val probes =
            listOf(
                HttpMethod.GET to "/api/v1/users/me",
                HttpMethod.GET to "/api/v1/chats",
                HttpMethod.POST to "/api/v1/chats/ensure",
                HttpMethod.GET to "/api/v1/chats/$chatId",
                HttpMethod.GET to "/api/v1/chats/$chatId/messages",
                HttpMethod.POST to "/api/v1/chats/$chatId/messages",
                HttpMethod.POST to "/api/v1/chats/$randomChatId/messages",
                HttpMethod.POST to "/api/v1/contacts",
                HttpMethod.GET to "/api/v1/contacts",
                HttpMethod.POST to "/api/v1/groups",
                HttpMethod.GET to "/api/v1/users/search?query=a",
            )

        val verdicts = probes.map { (method, path) -> bearerLess(method, path) to path }

        verdicts.forEach { (response, path) ->
            assertThat(response.statusCode)
                .overridingErrorMessage(
                    "a Bearer-less call to <%s> must answer 401, got <%s>: %s",
                    path,
                    response.statusCode,
                    response.body,
                ).isEqualTo(HttpStatus.UNAUTHORIZED)
            assertThat(response.headers.contentType?.toString())
                .overridingErrorMessage("the boundary refusal of <%s> must be RFC 9457 application/problem+json", path)
                .contains(PROBLEM_JSON_MEDIA_TYPE)
        }

        val bodies = verdicts.map { it.first.body }
        assertThat(bodies.toSet())
            .overridingErrorMessage(
                "the boundary 401 must be the ONE static body everywhere (no endpoint-specific disclosure), got: %s",
                bodies.toSet(),
            ).hasSize(1)
        val body = bodies.first()
        assertThat(body)
            .overridingErrorMessage("the boundary body must carry the static minimum and nothing else")
            .isEqualTo(UNAUTHORIZED_BODY)
        // no existence oracle: the body of a REAL chat id and of a
        // random UUID are the same bytes — already proven by the set
        // assertion above; pinned once more for the pair itself
        assertThat(bearerLess(HttpMethod.GET, "/api/v1/chats/$chatId").body)
            .isEqualTo(bearerLess(HttpMethod.GET, "/api/v1/chats/$UUID_RANDOM").body)
    }

    /**
     * FR-009 edge / T065: the whole member+moderation conferences
     * surface №44–№53 stays behind the Bearer boundary — the guest's
     * ONLY doors are №54/№55 (pinned below). The chain refuses before
     * any controller: a Bearer-less №44 writes NO conference row and
     * NO invite widget even against a perfectly valid chat.
     */
    @Test
    fun `the conferences member and moderation surface refuses a bearer-less caller`() {
        val (owner, peer) = messagingPair()
        val chatId = ensureChatOk(owner, peer.id)
        val conferenceId = UUID_RANDOM

        val probes =
            listOf(
                HttpMethod.POST to "/api/v1/chats/$chatId/conferences",
                HttpMethod.GET to "/api/v1/conferences/$conferenceId",
                HttpMethod.GET to "/api/v1/conferences/$conferenceId/me",
                HttpMethod.POST to "/api/v1/conferences/$conferenceId/join",
                HttpMethod.POST to "/api/v1/conferences/$conferenceId/end",
                HttpMethod.PUT to "/api/v1/conferences/$conferenceId/participants/u:$UUID_RANDOM/restrictions",
                HttpMethod.PUT to "/api/v1/conferences/$conferenceId/participants/u:$UUID_RANDOM/role",
                HttpMethod.DELETE to "/api/v1/conferences/$conferenceId/participants/u:$UUID_RANDOM",
                HttpMethod.POST to "/api/v1/conferences/$conferenceId/guest-invite",
                HttpMethod.DELETE to "/api/v1/conferences/$conferenceId/guest-invite",
            )

        probes.forEach { (method, path) ->
            val response = bearerLess(method, path)
            assertThat(response.statusCode)
                .overridingErrorMessage(
                    "a Bearer-less call to the conferences surface <%s> must answer 401, got <%s>: %s",
                    path,
                    response.statusCode,
                    response.body,
                ).isEqualTo(HttpStatus.UNAUTHORIZED)
            assertThat(response.body).isEqualTo(UNAUTHORIZED_BODY)
        }

        assertThat(conferenceRowsOf(chatId))
            .overridingErrorMessage("a Bearer-less №44 must not write a conference row (the chain rejects first)")
            .isEmpty()
        assertThat(systemMessageRowsOf(chatId))
            .overridingErrorMessage(
                "a Bearer-less №44 must not write the invite widget (no side effects past the boundary)",
            ).isEmpty()
    }

    /**
     * FR-009: the ONLY Bearer-less `conferences` surface is the guest
     * pair — №54/№55 answer their CONTRACT verdicts (never the
     * boundary 401), and the forged nonce folds into the
     * minimum-disclosure shapes of T046: the bare `{valid:false}`
     * with NO reason/live and the uniform `404 invite_invalid` — no
     * conference details, no causes of forgery (SC-003; the static
     * audit adds: the submitted nonce is never echoed anywhere in
     * the problem bodies).
     */
    @Test
    fun `the public guest pair is the only bearer-less conferences surface`() {
        val info = inviteInfo(GARBAGE_NONCE, AUDIT_IP)
        assertThat(info.statusCode)
            .overridingErrorMessage(
                "№54 is the public half of the pair — a Bearer-less probe must NOT hit the 401 boundary",
            ).isEqualTo(HttpStatus.OK)
        val infoView = objectMapper.readTree(info.body)
        assertThat(infoView.get(VALID_FIELD)?.asBoolean())
            .overridingErrorMessage("a forged nonce never validates (SC-003)")
            .isFalse
        assertThat(infoView.fieldNames().asSequence().toList())
            .overridingErrorMessage(
                "the forged №54 answer is the bare {valid:false} — no reason, no live, no conference details",
            ).containsExactly(VALID_FIELD)

        val join = joinByInvite(GARBAGE_NONCE, "audit-guest", AUDIT_IP)
        assertThat(join.statusCode)
            .overridingErrorMessage(
                "№55 is the public half of the pair — the uniform invite_invalid fold, not the 401 boundary",
            ).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(join.headers.contentType?.toString()).contains(PROBLEM_JSON_MEDIA_TYPE)
        assertThat(problemCode(join, INVITE_TOKEN_FIELD)).isEqualTo(INVITE_INVALID_CODE)
        assertThat(join.body)
            .overridingErrorMessage("the №55 refusal must not echo the submitted nonce or the guest name")
            .doesNotContain(GARBAGE_NONCE)
            .doesNotContain("audit-guest")
    }

    /** A request with NO Authorization header at all — the guest shape of FR-009. */
    private fun bearerLess(
        method: HttpMethod,
        path: String,
    ): ResponseEntity<String> =
        auditRestTemplate.exchange(
            path,
            method,
            HttpEntity<Void>(null, HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }),
            String::class.java,
        )

    private companion object {
        /** The static body of UnifiedAuthenticationEntryPoint — the audit's expected uniform refusal. */
        const val UNAUTHORIZED_BODY = """{"title":"Unauthorized","status":401,"detail":"Not authenticated"}"""

        /** The forged-nonce shape of the T048 forgery fold — reused verbatim (SC-003). */
        const val GARBAGE_NONCE = "audit-garbage-nonce"

        /**
         * The dedicated audit source IP of the fresh 203.0.118.0/24
         * block (the guest IT owns .116, the lifecycle IT's rotator
         * .117, the messaging fixtures .114) — the two №54/№55 probes
         * stay far below their 30/1m and 10/1m buckets.
         */
        const val AUDIT_IP = "203.0.118.10"

        /** A fixed random UUID — the no-existence-oracle probe of the boundary legs. */
        const val UUID_RANDOM = "7e0a4d64-9c1b-4bd0-8d0f-3f0f1a2b3c4d"

        const val INVITE_INVALID_CODE = "invite_invalid"
    }
}
