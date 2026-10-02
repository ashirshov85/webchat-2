package webchat.backend.presence

import org.springframework.test.context.TestPropertySource
import webchat.backend.chats.MessagingTestSupport

/**
 * The shared base of the 007 presence ITs (T025 infra): every presence
 * scenario that simulates an EXPLICIT client close (`UserEventsStream.
 * close()`) needs the server to NOTICE the dropped emitter inside the
 * scenario budget — the deferred offline publish must be scheduled by the
 * unregister hook (T024) and executed by the offq poller (T025) within
 * hysteresis + a couple of poller ticks.
 *
 * Tomcat parks a suspended async/SSE connection without read interest: a
 * dead emitter is only discovered by a FAILED WRITE, and the only writer
 * of an idle stream is the 004 `:ka` liveness heartbeat at its contract
 * cadence 15 s (`chats.realtime.heartbeat`, pinned verbatim by
 * RealtimeSseIT — the production value stays untouched in
 * application.yml). The first post-close write may still land in the
 * kernel buffer (the peer's RST answers it), so honest detection costs up
 * to two ticks: at 15 s that is ~30 s — far beyond the T023/T026 window
 * budgets, in production harmlessly absorbed by the 90 s TTL and the
 * offq recheck-at-due-time (a late unregister of a superseded
 * registration resolves to Suppressed, the metro invariants hold for ANY
 * detection lag). The IT profile therefore tightens ONLY this forked
 * context's keepalive to 500 ms — [TestPropertySource] forks the Spring
 * context of the presence ITs away from the shared MessagingTestSupport
 * cache, so the 004–006 suites keep both the 15 s contract cadence and
 * their cached contexts.
 */
@TestPropertySource(properties = ["chats.realtime.heartbeat=500ms"])
abstract class PresenceTestSupport : MessagingTestSupport()
