/**
 * Typing indicator row «X печатает…» (feature 008a, US2, T037;
 * FR-006–FR-008, ui-behavior.md §2.2): the ephemeral tail of the open
 * chat's message feed — a SINGLE instance rendered by MessageList
 * after the feed (the prototype's `#typingRow` is likewise the last
 * element of `#messages`, chats.html §7; re-mounted per appearance,
 * never duplicated).
 *
 * Markup is the prototype's `.msg.them` row: the participant Avatar
 * (34px feed size, design-tokens §4 — initials from the display name
 * `initialsOf(name)` per the T020 source split, color from
 * `colorOf(username)`: renaming never recolors, FR-004; in a direct
 * chat the typist IS the peer, so the derivation is uniform) and the
 * `.bubble.typing-b` with three `.tlamp` dots (animation `lampBlink`
 * 1.2 s, delays .2/.4 s — CSS lives in chat-header.css) and the
 * italic `.typing-txt` label.
 *
 * Texts (§2.2, ui-behavior §5): one typist — «{имя} печатает…»,
 * N > 1 — «{имя первого} и ещё {N-1} печатают…» (FR-008
 * aggregation). The name arrives already resolved through the US1
 * chain (`resolveDisplayName` over the participant cache №11/№28 —
 * the wiring of T038); user content renders as React text only
 * (008 FR-033).
 *
 * An unknown `userId` is not rendered (§2.2): a participant whose
 * name could not be resolved drops out of the row AND out of the N
 * count — no new APIs are introduced (YAGNI); when nobody resolves,
 * the row is not rendered at all.
 *
 * prefers-reduced-motion (FR-004): the global rule of
 * theme/machine.css kills `lampBlink` — the lamps sit still.
 */
import { Avatar } from '../../ui/Avatar'
import { initialsOf } from '../../ui/avatar'

/** Feed avatar size — design-tokens §4 (лента 34px). */
const TYPING_AVATAR_SIZE = 34

/** A typing participant of the open chat as seen by the client cache. */
export interface TypingParticipant {
  /** The typing member's user id (typing.started / typing.stopped payload). */
  readonly userId: string
  /**
   * Display name resolved through the US1 chain (`resolveDisplayName`)
   * from the participant cache (№11 peer / №28 roster); absent — the
   * user is unknown to the client and is NOT rendered (§2.2).
   */
  readonly name?: string
  /** Avatar color source — the participant's username (FR-004); the stable user id is the fallback. */
  readonly username?: string
}

/** A participant with a resolved (trimmed, non-empty) display name. */
interface RenderableParticipant {
  readonly userId: string
  readonly name: string
  readonly username?: string
}

/**
 * Participants that actually render (§2.2): only those whose name
 * resolved from the client cache — unknown `userId`s drop out of the
 * row and out of the N count. Blank/whitespace names count as
 * unresolved (the same leniency as names.ts). Exported for
 * MessageList's empty-state gate.
 */
// eslint-disable-next-line react-refresh/only-export-components -- фильтр неотделим от строки-компонента (ui-behavior 008a §2.2: TypingRow = единственный потребитель, MessageList — только empty-state гейт)
export function renderableTyping(
  typing: readonly TypingParticipant[],
): readonly RenderableParticipant[] {
  const visible: RenderableParticipant[] = []
  for (const participant of typing) {
    const name = participant.name?.trim()
    if (name !== undefined && name !== '') {
      visible.push({ userId: participant.userId, name, username: participant.username })
    }
  }
  return visible
}

export interface TypingRowProps {
  /** Typing participants of the open chat (raw cache lookups — filtered inside). */
  readonly typing: readonly TypingParticipant[]
}

export function TypingRow({ typing }: TypingRowProps) {
  const [first, ...rest] = renderableTyping(typing)
  if (first === undefined) {
    return null
  }
  const text =
    rest.length === 0 ? `${first.name} печатает…` : `${first.name} и ещё ${rest.length} печатают…`
  return (
    <li className="typing-row msg them">
      <Avatar
        source={first.username ?? first.userId}
        initials={initialsOf(first.name)}
        size={TYPING_AVATAR_SIZE}
      />
      <div className="bubble typing-b">
        <span className="tlamp" aria-hidden="true" />
        <span className="tlamp d1" aria-hidden="true" />
        <span className="tlamp d2" aria-hidden="true" />
        <span className="typing-txt" aria-live="polite">
          {text}
        </span>
      </div>
    </li>
  )
}
