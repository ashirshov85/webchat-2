package webchat.backend.conferences

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.test.context.TestPropertySource
import java.security.KeyPairGenerator
import java.security.interfaces.ECPrivateKey
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Date
import java.util.UUID
import java.util.concurrent.TimeUnit

/** The inert context-separating knob of [ConferenceGuestAccessIT] — the WHY is its class KDoc. */
private const val CONTEXT_SEPARATING_PROPERTY = "conferences.reconnect-window=119s"

/**
 * T048 (tasks.md Phase 6, US4) — the story IT of the guest invite surface
 * against the REAL stack (the [ConferenceCreateJoinIT]/
 * [ConferenceModerationIT] shape: Testcontainers PG+Redis via
 * [ConferencesTestSupport], the shared LiveKit plane, №47/№55-minted
 * signal-only room connections). One pinned leg per rule of the T048 task
 * table:
 *
 *  * **SC-003, the forgery fold** — three distinct forgery modes (garbage,
 *    a tampered signature, a well-formed `typ:"guest-invite"` nonce signed
 *    by an UNKNOWN ES256 key) answer the SAME byte-identical `{valid:false}`
 *    with NO reason and the SAME uniform `404 invite_invalid` — the causes
 *    of forgery are never disclosed (api-contract.md 009 §1 edge);
 *  * **SC-003, the lifecycle legs** — the expired link (the staged row
 *    horizon: JWT `exp` equals the row `expires_at` by construction, T044)
 *    answers `reason:"expired"` / `409 invite_expired`; the №53-revoked
 *    link answers `reason:"revoked"` / `409 invite_revoked`; every refused
 *    №55 mints NOTHING (no guest row);
 *  * **the name shape** — a blank and a 51-character name answer `422
 *    name_invalid`, the 50-character boundary enters (Q19/Q7=A);
 *  * **SC-004, the flood** — №54 admits exactly the 30/1m canon and №55
 *    exactly the 10/1m canon per source IP, then answers the uniform 429
 *    problem+json + `Retry-After` (the intervally-refilled bucket can not
 *    be won back mid-window), the bucket state lives in Redis under
 *    `rl:ip:conferences-*`, a FRESH source keeps passing — and the
 *    background №12 API keeps its latency budget while the flood runs
 *    (Δp99 ≤ 10 %, the IT noise-guarded twin of the k6 smoke T058);
 *  * **Q13=B / invariant 4 (data-model)** — the №53 revocation disconnects
 *    the guests ENTERED under the link through the media plane while the
 *    member of the same room stays connected, and blocks every new entry;
 *  * **Q17=A** — a second №52 revokes the previous link in the same
 *    transaction (ONE active row survives, `ux_guest_invites_active`) and
 *    disconnects the old link's connected guest (the issue-side Q13 leg);
 *  * **кик ≠ бан (the guest edge of spec.md)** — the №51 kick of a guest
 *    breaks his live connection, and the still-active multi-use link
 *    re-admits him with a FRESH `g:{connId}` (Q2/Q7=A);
 *  * **Q23=A** — members and guests divide the ONE quota: two member
 *    connections + one guest fill the lowered room, the next member №47
 *    AND the next guest №55 answer `409 conference_full` writing nothing;
 *  * **Q14=A** — an authenticated chat member entering by the guest link
 *    (his Bearer on the public №55) is a GUEST: the `g:{connId}` identity,
 *    `{"role":"participant","guest":true,"inviteId}` metadata, a
 *    `user_id IS NULL` row — coexisting with his own member principal;
 *  * **the role gate** — a plain participant's №52/№53 answer `403
 *    moderator_required` with nothing written, and the moderator's №53
 *    without an active link answers the `404 guest_invite_not_found` fold.
 *
 * The per-IP buckets live in the SHARED static Redis for the whole test
 * JVM and never refill inside their 1 m window (intervally), so every
 * method owns a DEDICATED source IP of the 203.0.116.0/24 block —
 * untouched by the messaging fixtures (203.0.114.x), the generic rotator
 * (203.0.113.x), the presence logins (203.0.115.x) and the 002/003
 * suites' 198.18/192.0.2/198.51.100 blocks.
 *
 * The [TestPropertySource] knob below is the DISTINCT context
 * configuration the support class KDoc prescribes for «the later US4/US5
 * sets»: [ConferenceCreateJoinIT]'s last leg KILLS the shared plane, and a
 * class sharing its cached context would keep the dead container's URL (a
 * fresh context — this one — re-runs the self-healing
 * `conferences.livekit.url` supplier and binds the restarted plane). The
 * value itself is inert for these legs (`reconnect-window` is a client
 * budget no assertion reads); only the context key matters.
 */
@TestPropertySource(properties = [CONTEXT_SEPARATING_PROPERTY])
@Suppress("TooManyFunctions") // one leg per rule of the T048 task table
class ConferenceGuestAccessIT : ConferencesTestSupport() {
    @Autowired
    private lateinit var redisTemplate: StringRedisTemplate

    @Autowired
    private lateinit var meterRegistry: MeterRegistry

    /** SC-003: every forgery mode folds into ONE uniform refusal — №54 and №55 alike. */
    @Test
    fun `the forged nonce legs fold into one uniform refusal`() {
        val (owner, bob) = messagingPair()
        val chatId = ensureChatOk(owner, bob.id)
        val conferenceId = createConferenceOk(owner, chatId)
        val nonce = nonceOfInviteUrl(createGuestInviteOk(owner, conferenceId)[INVITE_URL_FIELD].asText())

        val usable = inviteInfo(nonce, FORGERY_IP)
        assertThat(usable.statusCode).isEqualTo(HttpStatus.OK)
        val usableView = objectMapper.readTree(usable.body)
        assertThat(usableView[VALID_FIELD].asBoolean()).isTrue
        assertThat(usableView[LIVE_FIELD].asBoolean())
            .overridingErrorMessage("№54 carries live=false while the conference is 'created' (nobody joined yet)")
            .isFalse
        assertThat(usableView.fieldNames().asSequence().toList())
            .overridingErrorMessage("№54 discloses the minimum: valid + live only (api-contract.md 009 §1)")
            .containsExactlyInAnyOrder(VALID_FIELD, LIVE_FIELD)

        val missing = inviteInfo(null, FORGERY_IP)
        assertThat(missing.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(problemCode(missing, INVITE_TOKEN_FIELD)).isEqualTo(MALFORMED_REQUEST_CODE)

        val forgeries = listOf(GARBAGE_NONCE, tampered(nonce), foreignKeyNonce())
        val forgedViews = forgeries.map { objectMapper.readTree(inviteInfo(it, FORGERY_IP).body) }
        forgedViews.forEach { view ->
            assertThat(view[VALID_FIELD].asBoolean())
                .overridingErrorMessage("a forged nonce never validates (SC-003)")
                .isFalse
            assertThat(view[REASON_FIELD])
                .overridingErrorMessage("a forged nonce carries NO reason — forgery causes are never disclosed")
                .isNull()
            assertThat(view[LIVE_FIELD]).isNull()
        }
        assertThat(forgedViews.map { it.toString() }.toSet())
            .overridingErrorMessage("the forgery modes are indistinguishable — byte-identical №54 answers (§1 edge)")
            .hasSize(1)

        forgeries.forEach { forgery ->
            val refused = joinByInvite(forgery, GUEST_NAME, FORGERY_IP)
            assertThat(refused.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
            assertThat(problemCode(refused, INVITE_TOKEN_FIELD)).isEqualTo(INVITE_INVALID_CODE)
        }

        assertThat(guestRowsOf(conferenceId))
            .overridingErrorMessage("a forged №55 mints nothing — no guest row exists")
            .isEmpty()
        assertThat(inviteRowsOf(conferenceId).single()[STATUS_COLUMN]).isEqualTo(ACTIVE_STATUS)
    }

    /** SC-003: the EXPIRED and REVOKED lifecycle refusals — disclosable reasons, nothing minted. */
    @Test
    fun `the expired and revoked links answer their lifecycle refusals`() {
        val (owner, bob) = messagingPair()
        val chatId = ensureChatOk(owner, bob.id)
        val conferenceId = createConferenceOk(owner, chatId)
        val firstNonce = nonceOfInviteUrl(createGuestInviteOk(owner, conferenceId)[INVITE_URL_FIELD].asText())

        stageExpiredInvite(conferenceId)
        val expiredView = objectMapper.readTree(inviteInfo(firstNonce, LIFECYCLE_IP).body)
        assertThat(expiredView[VALID_FIELD].asBoolean()).isFalse
        assertThat(expiredView[REASON_FIELD].asText()).isEqualTo(EXPIRED_REASON)
        val expiredJoin = joinByInvite(firstNonce, GUEST_NAME, LIFECYCLE_IP)
        assertThat(expiredJoin.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(problemCode(expiredJoin, INVITE_TOKEN_FIELD)).isEqualTo(INVITE_EXPIRED_CODE)

        val secondNonce = nonceOfInviteUrl(createGuestInviteOk(owner, conferenceId)[INVITE_URL_FIELD].asText())
        val freshView = objectMapper.readTree(inviteInfo(secondNonce, LIFECYCLE_IP).body)
        assertThat(freshView[VALID_FIELD].asBoolean())
            .overridingErrorMessage("a fresh №52 link is usable again after the expired one")
            .isTrue

        val revoked = revokeGuestInvite(owner, conferenceId)
        assertThat(revoked.statusCode).isEqualTo(HttpStatus.NO_CONTENT)
        val revokedView = objectMapper.readTree(inviteInfo(secondNonce, LIFECYCLE_IP).body)
        assertThat(revokedView[VALID_FIELD].asBoolean()).isFalse
        assertThat(revokedView[REASON_FIELD].asText()).isEqualTo(REVOKED_REASON)
        val revokedJoin = joinByInvite(secondNonce, GUEST_NAME, LIFECYCLE_IP)
        assertThat(revokedJoin.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(problemCode(revokedJoin, INVITE_TOKEN_FIELD)).isEqualTo(INVITE_REVOKED_CODE)

        assertThat(guestRowsOf(conferenceId))
            .overridingErrorMessage("every refused №55 leg mints nothing — no guest row exists")
            .isEmpty()
        val rows = inviteRowsOf(conferenceId)
        assertThat(rows.map { it[STATUS_COLUMN] }.toSet())
            .overridingErrorMessage("after №53 no ACTIVE link survives")
            .containsExactly(REVOKED_STATUS)
        assertThat(rows).allMatch { it[REVOKED_AT_COLUMN] != null }
    }

    /** Q19/Q7=A: the guest name shape of №55 — blank and 51 chars refuse, the 50-char boundary enters. */
    @Test
    fun `the guest name shape of join-by-invite`() {
        val (owner, bob) = messagingPair()
        val chatId = ensureChatOk(owner, bob.id)
        val conferenceId = createConferenceOk(owner, chatId)
        val nonce = nonceOfInviteUrl(createGuestInviteOk(owner, conferenceId)[INVITE_URL_FIELD].asText())

        val blank = joinByInvite(nonce, "   ", NAME_IP)
        assertThat(blank.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
        assertThat(problemCode(blank, NAME_FIELD)).isEqualTo(NAME_INVALID_CODE)

        val tooLong = joinByInvite(nonce, "g".repeat(NAME_MAX_LENGTH + 1), NAME_IP)
        assertThat(tooLong.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
        assertThat(problemCode(tooLong, NAME_FIELD)).isEqualTo(NAME_INVALID_CODE)

        val boundary = joinByInvite(nonce, "g".repeat(NAME_MAX_LENGTH), NAME_IP)
        assertThat(boundary.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(ticketOf(boundary)[NAME_FIELD].asText()).hasSize(NAME_MAX_LENGTH)
    }

    /**
     * The role gate: a plain participant's №52/№53 answer 403 with nothing
     * written; the moderator's №53 without an active link is the 404 fold.
     */
    @Test
    fun `a non-moderator is refused 403 on the invite endpoints`() {
        val (owner, bob) = messagingPair()
        val chatId = ensureChatOk(owner, bob.id)
        val conferenceId = createConferenceOk(owner, chatId)
        joinConferenceOk(bob, conferenceId)

        val bobIssue = createGuestInvite(bob, conferenceId)
        assertThat(bobIssue.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(problemCode(bobIssue)).isEqualTo(MODERATOR_REQUIRED_CODE)

        val bobRevoke = revokeGuestInvite(bob, conferenceId)
        assertThat(bobRevoke.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(problemCode(bobRevoke)).isEqualTo(MODERATOR_REQUIRED_CODE)

        val ownerRevoke = revokeGuestInvite(owner, conferenceId)
        assertThat(ownerRevoke.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(problemCode(ownerRevoke, INVITE_FIELD)).isEqualTo(GUEST_INVITE_NOT_FOUND_CODE)

        assertThat(inviteRowsOf(conferenceId))
            .overridingErrorMessage("the refused gate wrote no invite row")
            .isEmpty()
    }

    /** Q13=B / data-model invariant 4: №53 disconnects the entered guests, keeps the members, blocks new entries. */
    @Test
    fun `revocation disconnects the entered guests and blocks new entries`() {
        val (owner, bob) = messagingPair()
        val chatId = ensureChatOk(owner, bob.id)
        val conferenceId = createConferenceOk(owner, chatId)
        val nonce = nonceOfInviteUrl(createGuestInviteOk(owner, conferenceId)[INVITE_URL_FIELD].asText())

        val guestConnection = connectTicket(ticketOf(joinByInviteOk(nonce, "guest-one", Q13_IP)))
        connectMember(bob, conferenceId)
        val room = roomNameOf(conferenceId)
        awaitRoomParticipants(room) { it.size == CONNECTED_TWO }

        val revoked = revokeGuestInvite(owner, conferenceId)
        assertThat(revoked.statusCode).isEqualTo(HttpStatus.NO_CONTENT)

        guestConnection.awaitClosedByServer()
        awaitRoomParticipants(room) { identities ->
            identities.any { it.startsWith(memberPrefixOf(bob.id)) } && identities.none { it.startsWith(GUEST_PREFIX) }
        }

        val refused = joinByInvite(nonce, "guest-two", Q13_IP)
        assertThat(refused.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(problemCode(refused, INVITE_TOKEN_FIELD)).isEqualTo(INVITE_REVOKED_CODE)
        val revokedRow = inviteRowsOf(conferenceId).single()
        assertThat(revokedRow[STATUS_COLUMN]).isEqualTo(REVOKED_STATUS)
        assertThat(revokedRow[REVOKED_AT_COLUMN]).isNotNull
    }

    /** Q17=A: a second №52 revokes the previous link in one transaction and disconnects the old link's guest. */
    @Test
    fun `generating a new link revokes the previous one`() {
        val (owner, bob) = messagingPair()
        val chatId = ensureChatOk(owner, bob.id)
        val conferenceId = createConferenceOk(owner, chatId)
        val firstNonce = nonceOfInviteUrl(createGuestInviteOk(owner, conferenceId)[INVITE_URL_FIELD].asText())

        val oldGuest = connectTicket(ticketOf(joinByInviteOk(firstNonce, "guest-old", Q17_IP)))
        val secondNonce = nonceOfInviteUrl(createGuestInviteOk(owner, conferenceId)[INVITE_URL_FIELD].asText())
        assertThat(secondNonce).isNotEqualTo(firstNonce)

        oldGuest.awaitClosedByServer()

        val oldView = objectMapper.readTree(inviteInfo(firstNonce, Q17_IP).body)
        assertThat(oldView[VALID_FIELD].asBoolean()).isFalse
        assertThat(oldView[REASON_FIELD].asText()).isEqualTo(REVOKED_REASON)
        val oldJoin = joinByInvite(firstNonce, "guest-old-again", Q17_IP)
        assertThat(oldJoin.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(problemCode(oldJoin, INVITE_TOKEN_FIELD)).isEqualTo(INVITE_REVOKED_CODE)

        val newTicket = ticketOf(joinByInviteOk(secondNonce, "guest-new", Q17_IP))
        assertThat(newTicket[IDENTITY_FIELD].asText()).startsWith(GUEST_PREFIX)

        val rows = inviteRowsOf(conferenceId)
        assertThat(rows.filter { it[STATUS_COLUMN] == ACTIVE_STATUS })
            .overridingErrorMessage("exactly ONE active link survives a №52 pair (ux_guest_invites_active, Q17=A)")
            .hasSize(1)
        assertThat(rows.filter { it[STATUS_COLUMN] == REVOKED_STATUS }.single()[REVOKED_AT_COLUMN]).isNotNull
    }

    /** кик ≠ бан (spec.md edge): the №51 kick breaks the guest's connection, the still-active link re-admits him. */
    @Test
    fun `a kicked guest re-enters by the still-active link`() {
        val (owner, bob) = messagingPair()
        val chatId = ensureChatOk(owner, bob.id)
        val conferenceId = createConferenceOk(owner, chatId)
        val nonce = nonceOfInviteUrl(createGuestInviteOk(owner, conferenceId)[INVITE_URL_FIELD].asText())

        val firstTicket = ticketOf(joinByInviteOk(nonce, "guest-kicked", KICK_IP))
        val firstIdentity = firstTicket[IDENTITY_FIELD].asText()
        val connection = connectTicket(firstTicket)

        val kicked = kickConferenceParticipant(owner, conferenceId, firstIdentity)
        assertThat(kicked.statusCode).isEqualTo(HttpStatus.NO_CONTENT)
        connection.awaitClosedByServer()

        val secondTicket = ticketOf(joinByInviteOk(nonce, "guest-kicked", KICK_IP))
        assertThat(secondTicket[IDENTITY_FIELD].asText())
            .overridingErrorMessage("the re-entry is a FRESH g:{connId} — the kick banned nothing (Q2/Q7=A)")
            .isNotEqualTo(firstIdentity)
        connectTicket(secondTicket)
        awaitRoomParticipants(roomNameOf(conferenceId)) { identities ->
            identities.contains(secondTicket[IDENTITY_FIELD].asText()) &&
                identities.none { it == firstIdentity }
        }

        assertThat(guestRowsOf(conferenceId))
            .overridingErrorMessage("every №55 entry is its OWN row (Q7=A) — the kick touched neither")
            .hasSize(2)
    }

    /** Q23=A: members and guests divide the ONE quota — the next №47 AND №55 both answer conference_full. */
    @Test
    fun `guests and members divide the one quota`() {
        val owner = messagingUser("owner")
        val bob = messagingUser("bob")
        val carol = messagingUser("carol")
        val chatId = groupChatOf(owner, bob, carol)
        val conferenceId = createConferenceOk(owner, chatId)

        connectMember(owner, conferenceId)
        connectMember(bob, conferenceId)
        val nonce = nonceOfInviteUrl(createGuestInviteOk(owner, conferenceId)[INVITE_URL_FIELD].asText())
        connectTicket(ticketOf(joinByInviteOk(nonce, "guest-quota", QUOTA_IP)))
        awaitRoomParticipants(roomNameOf(conferenceId)) { it.size == QUOTA }

        val refusedMember = joinConference(carol, conferenceId)
        assertThat(refusedMember.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(problemCode(refusedMember)).isEqualTo(CONFERENCE_FULL_CODE)

        val refusedGuest = joinByInvite(nonce, "guest-over", QUOTA_IP)
        assertThat(refusedGuest.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(problemCode(refusedGuest)).isEqualTo(CONFERENCE_FULL_CODE)

        assertThat(guestRowsOf(conferenceId))
            .overridingErrorMessage("the quota-refused №55 minted no second guest row")
            .hasSize(1)
        assertThat(participantRowsOf(conferenceId).map { it[USER_ID_COLUMN] })
            .doesNotContain(carol.id)
    }

    /**
     * Q14=A: an authenticated member entering by the guest link IS a guest —
     * a fresh principal, guest metadata and a `user_id IS NULL` row.
     */
    @Test
    fun `an authenticated member enters by the guest link as a guest`() {
        val (owner, bob) = messagingPair()
        val chatId = ensureChatOk(owner, bob.id)
        val conferenceId = createConferenceOk(owner, chatId)
        val nonce = nonceOfInviteUrl(createGuestInviteOk(owner, conferenceId)[INVITE_URL_FIELD].asText())

        val entered = joinByInvite(nonce, "bob-as-guest", Q14_IP, asUser = bob)
        assertThat(entered.statusCode).isEqualTo(HttpStatus.OK)
        val guestTicket = ticketOf(entered)
        val guestIdentity = guestTicket[IDENTITY_FIELD].asText()
        assertThat(guestIdentity).startsWith(GUEST_PREFIX)

        val memberIdentity = ticketOf(joinConferenceOk(bob, conferenceId))[IDENTITY_FIELD].asText()
        assertThat(memberIdentity)
            .overridingErrorMessage("the SAME person holds TWO distinct principals — member №47 and guest №55 (Q14=A)")
            .isNotEqualTo(guestIdentity)
        assertThat(memberIdentity).startsWith(MEMBER_PREFIX)

        val metadata =
            objectMapper.readTree(
                SignedJWT.parse(guestTicket[TOKEN_FIELD].asText()).jwtClaimsSet.getStringClaim(METADATA_FIELD),
            )
        assertThat(metadata[GUEST_FIELD].asBoolean()).isTrue
        assertThat(metadata[ROLE_FIELD].asText()).isEqualTo(PARTICIPANT_ROLE)
        assertThat(metadata[INVITE_ID_FIELD].asText())
            .isEqualTo(inviteRowsOf(conferenceId).single()[ID_COLUMN].toString())

        val guestRow = guestRowsOf(conferenceId).single()
        assertThat(guestRow[USER_ID_COLUMN])
            .overridingErrorMessage("the №55 entry is a GUEST row even for a known member (Q14=A)")
            .isNull()
        assertThat(guestRow[DISPLAY_NAME_COLUMN]).isEqualTo("bob-as-guest")
    }

    /** SC-004: №54 floods to the uniform 429 while the background №12 API keeps its Δp99 ≤ 10 % budget. */
    @Test
    fun `the invite-info flood answers 429 and the background api keeps its budget`() {
        val (alice, bob) = messagingPair()
        ensureChatOk(alice, bob.id)
        val rateLimitedBefore = rateLimitDenials()

        repeat(WARMUP_SAMPLES) { assertThat(listChats(alice).statusCode).isEqualTo(HttpStatus.OK) }
        val baselineP99 = p99MicrosOf(List(BASELINE_SAMPLES) { timedChatsRead(alice) })

        repeat(INVITE_INFO_LIMIT) {
            val response = inviteInfo(GARBAGE_NONCE, FLOOD_INFO_IP)
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(objectMapper.readTree(response.body)[VALID_FIELD].asBoolean()).isFalse
        }
        assertTooManyRequests(inviteInfo(GARBAGE_NONCE, FLOOD_INFO_IP))
        assertThat(redisTemplate.keys(INVITE_INFO_BUCKET_KEY_PREFIX + FLOOD_INFO_IP).orEmpty())
            .overridingErrorMessage("the bucket state lives in Redis under rl:ip:conferences-invite-info:* (FR-009)")
            .isNotEmpty()
        assertThat(inviteInfo(GARBAGE_NONCE, FRESH_CHECK_IP).statusCode)
            .overridingErrorMessage("a FRESH source sails through — the bucket is per-IP, never global")
            .isEqualTo(HttpStatus.OK)

        val duringFloodP99 =
            p99MicrosOf(
                List(BASELINE_SAMPLES) {
                    repeat(FLOOD_PER_SAMPLE) {
                        assertThat(inviteInfo(GARBAGE_NONCE, FLOOD_INFO_IP).statusCode)
                            .isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                    }
                    timedChatsRead(alice)
                },
            )
        assertThat(duringFloodP99)
            .overridingErrorMessage(
                "SC-004: the №54 flood must not degrade the rest of the API — flood p99 %d µs vs baseline %d µs " +
                    "(Δp99 <= 10%% + the %d µs in-process IT noise guard; the normative gate is the k6 profile T058)",
                duringFloodP99,
                baselineP99,
                NOISE_GUARD_MICROS,
            ).isLessThanOrEqualTo(baselineP99 + baselineP99 / DEGRADATION_BUDGET_DIVISOR + NOISE_GUARD_MICROS)
        assertThat(rateLimitDenials())
            .overridingErrorMessage(
                "the 429 legs count webchat_conference_joins_denied_total{reason=rate_limit} (FR-018/SC-009, T067)",
            ).isGreaterThan(rateLimitedBefore)
    }

    /**
     * SC-004: №55 floods to the uniform 429 at the stricter 10/1m canon —
     * nothing was minted by the throttled flood.
     */
    @Test
    fun `the join-by-invite flood answers 429 with nothing minted`() {
        val (owner, bob) = messagingPair()
        val chatId = ensureChatOk(owner, bob.id)
        val conferenceId = createConferenceOk(owner, chatId)

        repeat(JOIN_BY_INVITE_LIMIT) {
            val response = joinByInvite(GARBAGE_NONCE, GUEST_NAME, FLOOD_JOIN_IP)
            assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
            assertThat(problemCode(response, INVITE_TOKEN_FIELD)).isEqualTo(INVITE_INVALID_CODE)
        }
        assertTooManyRequests(joinByInvite(GARBAGE_NONCE, GUEST_NAME, FLOOD_JOIN_IP))
        assertThat(redisTemplate.keys(JOIN_BY_INVITE_BUCKET_KEY_PREFIX + FLOOD_JOIN_IP).orEmpty()).isNotEmpty

        assertThat(guestRowsOf(conferenceId))
            .overridingErrorMessage("a throttled flood never reaches the chain — no guest row was minted")
            .isEmpty()
        assertThat(rateLimitDenials())
            .overridingErrorMessage(
                "the №55 429 legs count webchat_conference_joins_denied_total{reason=rate_limit} " +
                    "(FR-018/SC-009, T067)",
            ).isGreaterThan(0.0)
    }

    /**
     * The FR-018 rate-limit denial counter of the T067 seam (find(): the
     * common `application` tag stays unasserted).
     */
    private fun rateLimitDenials(): Double =
        meterRegistry
            .find(JOINS_DENIED_METRIC)
            .tag(REASON_TAG, RATE_LIMIT_REASON)
            .counter()
            ?.count()
            ?: 0.0

    // --- the forgery builders and the SC-004 measurement helpers ----------------

    /**
     * Flips the last base64url character of the signature — a well-formed,
     * TAMPERED nonce. The tail char of a canonical 86-char ES256 encoding
     * carries only two SIGNIFICANT bits (its alphabet is exactly A/Q/g/w),
     * so the flip must change those top bits: an 'A'→'B' swap re-decodes to
     * byte-identical bytes for 1/4 of the signatures and would leave the
     * nonce VALID. The quadrant flip 'A'↔'g' / 'Q'↔'w' (XOR 0b100000)
     * always breaks the signature.
     */
    private fun tampered(nonce: String): String =
        nonce.dropLast(1) +
            when (val tail = nonce.last()) {
                'A' -> 'g'
                'Q' -> 'w'
                'g' -> 'A'
                'w' -> 'Q'
                else -> error("unexpected ES256 signature tail <$tail>")
            }

    /**
     * A well-formed `typ:"guest-invite"` ES256 nonce signed by an UNKNOWN
     * key (fresh P-256, foreign kid) — the strongest forgery mode: correct
     * shape and claims, only the signature betrays it.
     */
    private fun foreignKeyNonce(): String {
        val keyPair =
            KeyPairGenerator
                .getInstance("EC")
                .apply { initialize(ECGenParameterSpec("secp256r1")) }
                .generateKeyPair()
        val jwt =
            SignedJWT(
                JWSHeader
                    .Builder(JWSAlgorithm.ES256)
                    .type(JOSEObjectType.JWT)
                    .keyID("it-forged-kid")
                    .build(),
                JWTClaimsSet
                    .Builder()
                    .subject(UUID.randomUUID().toString())
                    .claim(CLAIM_CONFERENCE_ID, UUID.randomUUID().toString())
                    .claim(CLAIM_TOKEN_TYPE, TOKEN_TYPE_GUEST_INVITE)
                    .issueTime(Date.from(Instant.now().minusSeconds(1)))
                    .expirationTime(Date.from(Instant.now().plusSeconds(FOREIGN_NONCE_TTL_SECONDS)))
                    .build(),
            )
        jwt.sign(ECDSASigner(keyPair.private as ECPrivateKey))
        return jwt.serialize()
    }

    /** №55 happy-path fixture → the raw 200 response (the parsed ticket via [ticketOf]). */
    private fun joinByInviteOk(
        nonce: String,
        name: String,
        sourceIp: String,
    ): ResponseEntity<String> {
        val response = joinByInvite(nonce, name, sourceIp)
        assertThat(response.statusCode)
            .overridingErrorMessage(
                "fixture №55 of guest <$name> must answer 200, got <%s>: %s",
                response.statusCode,
                response.body,
            ).isEqualTo(HttpStatus.OK)
        return response
    }

    /** One timed №12 read of the flood's «остальные API» control — µs, asserting the 200. */
    private fun timedChatsRead(user: MessagingUser): Long {
        val startedAt = System.nanoTime()
        val response = listChats(user)
        assertThat(response.statusCode)
            .overridingErrorMessage("the background №12 control must stay 200 through the flood")
            .isEqualTo(HttpStatus.OK)
        return TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - startedAt)
    }

    /** The p99 (µs) of the sample list — the 99th of the sorted latencies. */
    private fun p99MicrosOf(samples: List<Long>): Long =
        samples.sorted()[P99_NUMERATOR * (samples.size - 1) / P99_DENOMINATOR]

    /** The uniform 429 of the T047 buckets: problem+json + `Retry-After` within [1, 60] (1m windows). */
    private fun assertTooManyRequests(response: ResponseEntity<String>) {
        assertThat(response.statusCode).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
        val retryAfter = response.headers.getFirst(HttpHeaders.RETRY_AFTER)
        assertThat(retryAfter).isNotNull
        assertThat(retryAfter!!.toInt()).isBetween(MIN_RETRY_AFTER_SECONDS, MINUTE_SECONDS)
        assertThat(response.headers.contentType!!.toString()).contains(PROBLEM_JSON_MEDIA_TYPE)
        val problem = objectMapper.readTree(response.body)
        assertThat(problem[JSON_STATUS_FIELD].asInt()).isEqualTo(TOO_MANY_REQUESTS_STATUS)
        assertThat(problem[JSON_TITLE_FIELD].asText()).isEqualTo(TOO_MANY_REQUESTS_TITLE)
    }

    private companion object {
        /** api-contract.md 009 §3 canon: the per-IP limits of the public guest routes (T047). */
        const val INVITE_INFO_LIMIT = 30

        const val JOIN_BY_INVITE_LIMIT = 10

        const val NAME_MAX_LENGTH = 50

        const val MIN_RETRY_AFTER_SECONDS = 1

        const val MINUTE_SECONDS = 60

        const val TOO_MANY_REQUESTS_STATUS = 429

        /** The FR-018 denial-counter coordinates of the T067 observability seam. */
        const val JOINS_DENIED_METRIC = "webchat_conference_joins_denied_total"

        const val REASON_TAG = "reason"

        const val RATE_LIMIT_REASON = "rate_limit"

        const val WARMUP_SAMPLES = 10

        const val BASELINE_SAMPLES = 30

        const val FLOOD_PER_SAMPLE = 2

        const val DEGRADATION_BUDGET_DIVISOR = 10

        const val NOISE_GUARD_MICROS = 25_000L

        const val P99_NUMERATOR = 99

        const val P99_DENOMINATOR = 100

        const val CONNECTED_TWO = 2

        const val FOREIGN_NONCE_TTL_SECONDS = 3600L

        const val GUEST_NAME = "guest"

        const val GARBAGE_NONCE = "sc-004-flood-nonce-not-a-jwt"

        const val GUEST_PREFIX = "g:"

        const val MEMBER_PREFIX = "u:"

        const val ACTIVE_STATUS = "active"

        const val REVOKED_STATUS = "revoked"

        const val EXPIRED_REASON = "expired"

        const val REVOKED_REASON = "revoked"

        const val MALFORMED_REQUEST_CODE = "malformed_request"

        const val INVITE_INVALID_CODE = "invite_invalid"

        const val INVITE_EXPIRED_CODE = "invite_expired"

        const val INVITE_REVOKED_CODE = "invite_revoked"

        const val NAME_INVALID_CODE = "name_invalid"

        const val GUEST_INVITE_NOT_FOUND_CODE = "guest_invite_not_found"

        const val CONFERENCE_FULL_CODE = "conference_full"

        const val CLAIM_CONFERENCE_ID = "cid"

        const val CLAIM_TOKEN_TYPE = "typ"

        const val TOKEN_TYPE_GUEST_INVITE = "guest-invite"

        const val ID_COLUMN = "id"

        const val JSON_STATUS_FIELD = "status"

        const val JSON_TITLE_FIELD = "title"

        const val TOO_MANY_REQUESTS_TITLE = "Too Many Requests"

        const val INVITE_INFO_BUCKET_KEY_PREFIX = "rl:ip:conferences-invite-info:"

        const val JOIN_BY_INVITE_BUCKET_KEY_PREFIX = "rl:ip:conferences-join-by-invite:"

        // one dedicated source IP per method — the buckets never refill
        // inside their 1m intervally window (shared static Redis)
        const val FORGERY_IP = "203.0.116.10"

        const val LIFECYCLE_IP = "203.0.116.11"

        const val NAME_IP = "203.0.116.12"

        const val Q13_IP = "203.0.116.13"

        const val Q17_IP = "203.0.116.14"

        const val KICK_IP = "203.0.116.15"

        const val QUOTA_IP = "203.0.116.16"

        const val Q14_IP = "203.0.116.17"

        const val FLOOD_INFO_IP = "203.0.116.18"

        const val FLOOD_JOIN_IP = "203.0.116.19"

        const val FRESH_CHECK_IP = "203.0.116.20"
    }
}
