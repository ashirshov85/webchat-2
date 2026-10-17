package webchat.backend.conferences.api

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import java.util.stream.Stream

/**
 * T019 — unit-level acceptance of the RFC 9457 problem+json mapping
 * the [ConferencesExceptionHandler] renders, written per the
 * constitution VI discipline (the [webchat.backend.groups.api.GroupsExceptionHandlerTest]
 * convention): every failure leaves the controller as a typed
 * exception and reaches the client as `application/problem+json` with
 * the contract code inside `errors: map<string, string[]>` — codes and
 * field names only, never chat contents, roster details or conference
 * ids (constitution V).
 *
 * The table pins the whole №44–№47 refusal surface of api-contract.md
 * 009 §2: the `conference` field carries the conferences family
 * (`403 conference_forbidden`, `404 conference_not_found`, `409
 * conference_e2ee_unsupported | conference_ended | conference_full`,
 * `503 conference_media_unavailable`), the №44 chat resolution sits on
 * `chat`, and the request-shape 400s bind `mode` (`invalid_mode`) and
 * the raw path segments (`chatId`/`conferenceId: [invalid_uuid]`).
 * The moderation/guest codes (`403 moderator_required`, `404
 * participant_not_found`, `404 guest_invite_not_found`, the №54/№55
 * family) join with their T039/T045/T046 carriers.
 */
class ConferencesExceptionHandlerTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("mappingCases")
    fun `every typed refusal renders its contract status and problem code`(
        code: String,
        status: HttpStatus,
        field: String,
        rendering: () -> ProblemDetail,
    ) {
        val problem = rendering()

        assertThat(problem.status)
            .overridingErrorMessage("$code must render as HTTP ${status.value()}")
            .isEqualTo(status.value())

        val errors = errorsOf(problem)
        assertThat(errors.values.flatten())
            .overridingErrorMessage("$code must arrive as the single problem code of the errors map")
            .containsExactly(code)
        assertThat(errors)
            .overridingErrorMessage("$code must sit on the \"$field\" field of the errors map")
            .containsOnlyKeys(field)
    }

    companion object {
        private val handler = ConferencesExceptionHandler()

        @JvmStatic
        fun mappingCases(): Stream<Arguments> =
            listOf(
                // №44 chat resolution — answered before the membership gate (T017)
                case("chat_not_found", HttpStatus.NOT_FOUND, FIELD_CHAT) { handler.onConferenceChatNotFound() },
                // FR-003/SC-002 — the every-operation membership refusal of №44/№47
                case("conference_forbidden", HttpStatus.FORBIDDEN, FIELD_CONFERENCE) {
                    handler.onConferenceForbidden()
                },
                // §1 privacy core — the uniform 404 of the member reads №45–№53
                case("conference_not_found", HttpStatus.NOT_FOUND, FIELD_CONFERENCE) { handler.onConferenceNotFound() },
                // FR-001 — the v1 boundary with 010
                case("conference_e2ee_unsupported", HttpStatus.CONFLICT, FIELD_CONFERENCE) {
                    handler.onConferenceE2eeUnsupported()
                },
                // the «конференция завершена» answer of an old link (№47/№48/№52/№53/№55)
                case("conference_ended", HttpStatus.CONFLICT, FIELD_CONFERENCE) { handler.onConferenceEnded() },
                // FR-015/Q23=A — the shared member+guest quota (№47/№55)
                case("conference_full", HttpStatus.CONFLICT, FIELD_CONFERENCE) { handler.onConferenceFull() },
                // the media-plane edge — №44 pre-flight and №47 mint-time (research §7)
                case("conference_media_unavailable", HttpStatus.SERVICE_UNAVAILABLE, FIELD_CONFERENCE) {
                    handler.onConferenceMediaUnavailable()
                },
                // №44 request shape — the one body refusal
                case("invalid_mode", HttpStatus.BAD_REQUEST, FIELD_MODE) { handler.onInvalidConferenceMode() },
                // №44/№45–№47 path shape — the raw-segment gates
                case("invalid_uuid", HttpStatus.BAD_REQUEST, FIELD_CHAT_ID) { handler.onInvalidConferenceChatId() },
                case("invalid_uuid", HttpStatus.BAD_REQUEST, FIELD_CONFERENCE_ID) { handler.onInvalidConferenceId() },
                // T039 №48–№51 — the target-blind role gate of the equal moderators (Q21=A)
                case("moderator_required", HttpStatus.FORBIDDEN, FIELD_CONFERENCE) { handler.onModeratorRequired() },
                // T039 №49/№50/№51 — the folded moderation target (ended roster is indistinguishable)
                case("participant_not_found", HttpStatus.NOT_FOUND, FIELD_PARTICIPANT) {
                    handler.onParticipantNotFound()
                },
                // T039 №49/№50/№51 request shape — the identity segment and the two bodies
                case("malformed_request", HttpStatus.BAD_REQUEST, FIELD_PARTICIPANT_IDENTITY) {
                    handler.onInvalidParticipantIdentity()
                },
                case("malformed_request", HttpStatus.BAD_REQUEST, FIELD_PARTICIPANT_RESTRICTIONS) {
                    handler.onInvalidParticipantRestrictions()
                },
                case("invalid_role", HttpStatus.BAD_REQUEST, FIELD_PARTICIPANT_ROLE) {
                    handler.onInvalidParticipantRole()
                },
            ).stream()

        private fun case(
            code: String,
            status: HttpStatus,
            field: String,
            rendering: () -> ProblemDetail,
        ): Arguments = Arguments.of(code, status, field, rendering)

        @Suppress("UNCHECKED_CAST")
        private fun errorsOf(problem: ProblemDetail): Map<String, List<String>> =
            problem.properties?.get(ERRORS_PROPERTY) as? Map<String, List<String>> ?: emptyMap()

        private const val ERRORS_PROPERTY = "errors"
        private const val FIELD_CHAT = "chat"
        private const val FIELD_CHAT_ID = "chatId"
        private const val FIELD_CONFERENCE = "conference"
        private const val FIELD_CONFERENCE_ID = "conferenceId"
        private const val FIELD_MODE = "mode"
        private const val FIELD_PARTICIPANT = "participant"
        private const val FIELD_PARTICIPANT_IDENTITY = "identity"
        private const val FIELD_PARTICIPANT_RESTRICTIONS = "restrictions"
        private const val FIELD_PARTICIPANT_ROLE = "role"
    }
}
