package webchat.backend.chats

import org.springframework.test.context.TestPropertySource

/**
 * 008a T027 (tasks.md Phase 4, US2): the shared base of the typing ITs —
 * the [webchat.backend.presence.PresenceTestSupport] pattern (007 T025)
 * applied to the typing transition poller.
 *
 * The tightened typing windows themselves come JVM-wide from
 * application-test.yml (T008): `chats.typing.state-ttl` 3 s and
 * `chats.typing.poll-interval` 200 ms — the SC-003 self-expiry budget
 * (stopped ≤ TTL + poll-interval) must not flake on poller slack, and no
 * scenario may wait out the production 8 s TTL. The flood capacities
 * keep their production values: the TypingIT flood leg drains the real
 * 60/min bucket on purpose.
 *
 * T036 ownership rule (the presence precedent): application-test.yml
 * disables the typing poller for EVERY test context of the JVM (many
 * cached contexts would otherwise race their pollers on the ONE shared
 * Redis for the very state these ITs await); THIS base re-enables it —
 * the typing context is the ONE typing-poller owner of the run, the
 * deterministic single-replica view the SC-003 budget assumes
 * (production always runs it: the gate defaults to TRUE).
 *
 * [TestPropertySource] forks the Spring context of the typing ITs away
 * from the shared MessagingTestSupport cache, so the 004–006 suites keep
 * their cached contexts and the poller stays off there.
 */
@TestPropertySource(
    properties = [
        "chats.typing.poller-enabled=true",
    ],
)
abstract class TypingTestSupport : MessagingTestSupport()
