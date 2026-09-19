package webchat.backend.sso

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component

/**
 * T048 [US5] — the SSO observability vocabulary of research.md §14:
 * `sso_flow_total{provider, outcome}` counts every flow transition and
 * `sso_idp_call_duration{provider, kind}` times the external IdP legs,
 * both per provider (isolation, US4-4) and both visible on
 * `/actuator/prometheus` (quickstart S6).
 *
 * Flow outcomes (research.md §14): `started` (authorize / link-authorize),
 * `completed` (handshake issued or identity linked), `login_failed`
 * (identity resolution refused the login), `flow_rejected` (state/replay/
 * provider-disabled/link-refused) and `provider_error` (any IdP leg
 * failure — the same events that journal `sso_flow_error`, FR-011).
 *
 * IdP call kinds: `token` (code exchange), `jwks` (ID-token verification
 * incl. the lazily fetched key set) and `userinfo` — the profile leg of the
 * `oauth2-userinfo` branch (T057, research.md §19) — plus `emails`, the
 * GitHub-style verified-emails list behind the profile; the access token
 * behind both legs is consumed on the spot and never stored (FR-016).
 *
 * Tag values carry the provider id or the `unknown` marker of flows
 * rejected before their provider is known — never tokens, codes or
 * secrets (SC-004, FR-011).
 */
@Component
class SsoMetrics(
    private val meterRegistry: MeterRegistry,
) {
    /** Counts one `sso_flow_total{provider, outcome}` transition. */
    fun countFlow(
        providerId: String?,
        outcome: FlowOutcome,
    ) {
        meterRegistry
            .counter(FLOW_TOTAL, TAG_PROVIDER, providerId ?: UNKNOWN_PROVIDER, TAG_OUTCOME, outcome.value)
            .increment()
    }

    /**
     * Starts a `sso_idp_call_duration{provider, kind}` sample; the caller
     * stops it with [FlowSample.stop] on BOTH success and failure paths —
     * a timed-out or errored provider call is exactly the signal the timer
     * exists for (SC-005).
     */
    fun startIdpCall(
        providerId: String,
        kind: IdpCallKind,
    ): FlowSample = FlowSample(Timer.start(meterRegistry), providerId, kind)

    private fun idpCallTimer(
        providerId: String,
        kind: IdpCallKind,
    ): Timer =
        Timer
            .builder(IDP_CALL_DURATION)
            .description("Duration of the external identity-provider calls of the SSO callback (SC-005)")
            .tag(TAG_PROVIDER, providerId)
            .tag(TAG_KIND, kind.value)
            .register(meterRegistry)

    /** A running `sso_idp_call_duration` measurement stopped exactly once. */
    inner class FlowSample(
        private val sample: Timer.Sample,
        private val providerId: String,
        private val kind: IdpCallKind,
    ) {
        fun stop() {
            sample.stop(idpCallTimer(providerId, kind))
        }
    }

    /** research.md §14 outcome vocabulary of `sso_flow_total`. */
    enum class FlowOutcome(
        val value: String,
    ) {
        STARTED("started"),
        COMPLETED("completed"),
        LOGIN_FAILED("login_failed"),
        FLOW_REJECTED("flow_rejected"),
        PROVIDER_ERROR("provider_error"),
    }

    /** research.md §14 kind vocabulary of `sso_idp_call_duration`. */
    enum class IdpCallKind(
        val value: String,
    ) {
        TOKEN("token"),
        USERINFO("userinfo"),
        JWKS("jwks"),

        /** GitHub: the verified-emails list leg behind the profile (same Bearer token, FR-016). */
        EMAILS("emails"),
    }

    private companion object {
        // research.md §14 observability contract names
        const val FLOW_TOTAL = "sso_flow_total"

        const val IDP_CALL_DURATION = "sso_idp_call_duration"

        const val TAG_PROVIDER = "provider"

        const val TAG_OUTCOME = "outcome"

        const val TAG_KIND = "kind"

        /** Same marker as the `sso_flow_error` journal details (SsoController). */
        const val UNKNOWN_PROVIDER = "unknown"
    }
}
