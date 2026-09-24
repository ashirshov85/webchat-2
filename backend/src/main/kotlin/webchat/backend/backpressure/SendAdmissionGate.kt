package webchat.backend.backpressure

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * The admission port of the №16 send path (T039; research.md §6,
 * data-model сущность 6): the per-instance, overload-only gate
 * `MessageService` consults STRICTLY AFTER the FR-004 dedup fast-path and
 * BEFORE the membership, validation and flood legs (the №16 check order of
 * api-contract.md §3, pinned by BackpressureIT):
 *
 *  * a retry of an already recorded id never reaches the gate — it has
 *    resolved through the dedup first, so it can never be shed (FR-009:
 *    the retry answers `200`, never 503/429);
 *  * a shed ([AdmissionDecision.Shed]) renders as the contract `503
 *    server_busy` + `Retry-After` (the api layer of T041 owns the
 *    problem+json and the header), writes NOTHING and burns no flood
 *    token (FR-010): the per-user 30/minute allowance stays behind the
 *    system signal and intact once the spike subsides;
 *  * an admitted send ([AdmissionDecision.Admitted]) holds one in-flight
 *    slot for its synchronous send leg and MUST settle the lease exactly
 *    once (try/`use`) — on any outcome, a typed refusal included.
 *
 * The legs beyond the gate carry no admission at all: the realtime fan-out
 * and the pull synchronization of ACCEPTED messages run ungated («принятые
 * доставляются в приоритете», US4-4) — overload may only slow ACCEPTANCE.
 *
 * This is transport state, not business state: the adaptive implementation
 * (T040) is in-memory per-instance and self-heals by measuring the path
 * latency, so a reset/restart is safe (constitution II — the SSE-registry
 * precedent of 004). The DIP split is deliberate (plan.md VIII): the send
 * path depends on this port alone, the limiter is wired from the outside
 * exactly the way `SendPolicyGate` was in 004.
 */
interface SendAdmissionGate {
    /**
     * Judges one №16 send attempt against the instance's admission budget.
     * [chatId]/[senderId] identify the attempt for the observability
     * contract of research.md §8 (the shed warn log and metrics — ids
     * only, never the message text); the decision itself is per-INSTANCE,
     * not per-user, and never reads or stores the payload.
     */
    fun admit(
        chatId: UUID,
        senderId: UUID,
    ): AdmissionDecision
}

/**
 * The verdict of [SendAdmissionGate.admit] for one №16 send attempt:
 * either the send proceeds holding one in-flight slot, or the instance
 * sheds it as overloaded.
 */
sealed interface AdmissionDecision {
    /**
     * The send may proceed: it holds one in-flight admission slot until
     * [lease] settles. The caller owns the lease lifecycle — [lease] must
     * be closed exactly once (try/`use`), whatever the send outcome.
     */
    data class Admitted(
        val lease: AdmissionLease,
    ) : AdmissionDecision

    /**
     * The send is shed — the instance is loaded beyond its adaptive limit.
     * [retryAfterSeconds] is the integral drain estimate of research.md §6
     * (`ceil(in-flight × EWMA / max(limit, 1))`, floored at 1 second): the
     * api layer renders it as the `Retry-After` of the `503 server_busy`
     * problem+json. A shed writes nothing and burns no flood token.
     */
    data class Shed(
        val retryAfterSeconds: Long,
    ) : AdmissionDecision
}

/**
 * The in-flight slot of one ADMITTED №16 send attempt.
 *
 * [acked] marks the attempt as durably settled — the `201 Created` /
 * `200 Existing` outcomes, exactly the sampling convention of
 * `webchat_message_ack_seconds`: a refused send abandons its latency
 * sample and never deflates the limiter's overload signal (a fast 4xx/429
 * never measured the PG-commit leg it would have polluted).
 *
 * [close] settles the attempt: it releases the in-flight slot and, after
 * [acked], feeds the admission-to-settle hold duration into the limiter's
 * latency signal (the EWMA of the PG-commit path, T040). The send path's
 * try/`use` guarantees the close on every outcome — a typed refusal
 * thrown after admission releases its slot WITHOUT a sample.
 */
interface AdmissionLease : AutoCloseable {
    /** Marks the attempt as acked: its hold duration becomes a latency sample at [close]. */
    fun acked()

    /** Settles the attempt: releases the in-flight slot, recording the sample if [acked] was called. */
    override fun close()
}

/**
 * The `delivery.backpressure.enabled=false` profile (tasks.md T038:
 * «при `delivery.backpressure.enabled=false` путь отправки не меняется»,
 * pinned by BackpressureDisabledIT): the gate admits every attempt
 * instantly and the lease is inert, so the send path behaves EXACTLY as
 * before the feature — nothing can ever answer `503 server_busy`, and the
 * FR-011 flood limiter stays the one and only refusal of №16.
 */
@ConditionalOnProperty(
    prefix = "delivery.backpressure",
    name = ["enabled"],
    havingValue = "false",
)
@Component
class NoopSendAdmissionGate : SendAdmissionGate {
    private val inertLease: AdmissionLease =
        object : AdmissionLease {
            override fun acked() = Unit

            override fun close() = Unit
        }

    override fun admit(
        chatId: UUID,
        senderId: UUID,
    ): AdmissionDecision = AdmissionDecision.Admitted(inertLease)
}
