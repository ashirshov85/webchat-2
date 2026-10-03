import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Message } from '../../../api/chats'
import type { GroupMember } from '../../../api/groups'
import { MessageList } from '../MessageList'

/**
 * Delivery-stamp animation (feature 008, US1, T023, FR-018 edge case):
 * the engraved ✓/✓✓ ticks play the prototype `tickStamp` (.32s)
 * ONLY when the stamp of an ALREADY DISPLAYED row changes —
 * «телеграф отбил» (local optimistic row → server ✓) and ✓ → ✓✓
 * (watermark advance). The first paint of history (initial load,
 * pagination prepend, chat switch) renders settled stamps, and a
 * mass watermark jump re-renders ONLY the flipped rows — no
 * re-render avalanche over the whole feed.
 *
 * `avatarRenders` counts the row Avatar renders (one per
 * FeedRow/LocalRow render): the mock replaces ui/Avatar for THIS
 * suite only, turning «only the flipped rows re-render» into an
 * exact assertion.
 */
const stamps = vi.hoisted(() => ({ avatarRenders: 0 }))

vi.mock('../../../ui/Avatar', () => ({
  Avatar: () => {
    stamps.avatarRenders += 1
    return null
  },
}))

const ME = '11111111-1111-1111-1111-111111111111'
const PEER = '22222222-2222-2222-2222-222222222222'

function outgoing(id: string, seq: number): Message {
  return {
    id,
    chatId: 'chat-1',
    senderId: ME,
    text: `text-${id}`,
    seq,
    createdAt: '2026-09-20T12:00:00.000Z',
  }
}

function incoming(id: string, seq: number): Message {
  return {
    id,
    chatId: 'chat-1',
    senderId: PEER,
    text: `text-${id}`,
    seq,
    createdAt: '2026-09-20T12:00:00.000Z',
  }
}

function groupMember(id: string, username: string): GroupMember {
  return {
    user: {
      id,
      username,
      email: `${username}@example.com`,
      status: 'active',
      createdAt: '2026-09-01T00:00:00.000Z',
    },
    role: 'member',
    joinedAt: '2026-09-01T00:00:00.000Z',
  }
}

function animTicks(container: HTMLElement): HTMLElement[] {
  return Array.from(container.querySelectorAll<HTMLElement>('.tick.anim'))
}

beforeEach(() => {
  stamps.avatarRenders = 0
})

afterEach(() => {
  cleanup()
})

describe('MessageList tickStamp: first paint stays settled', () => {
  it('renders history stamps (✓ and ✓✓ alike) without the tickStamp animation', () => {
    const { container } = render(
      <MessageList
        messages={[outgoing('out-1', 2), outgoing('out-2', 3)]}
        currentUserId={ME}
        peerReadUpToSeq={2}
      />,
    )

    expect(container.querySelectorAll('.tick.dlv')).toHaveLength(1)
    expect(container.querySelectorAll('.tick.read')).toHaveLength(1)
    // T023: a fresh paint is not a status change — no stamp animates.
    expect(animTicks(container)).toHaveLength(0)
  })

  it('renders an older pagination page as settled stamps (no animation)', () => {
    const { container, rerender } = render(
      <MessageList messages={[outgoing('out-3', 3)]} currentUserId={ME} />,
    )
    rerender(
      <MessageList
        messages={[outgoing('out-1', 1), outgoing('out-2', 2), outgoing('out-3', 3)]}
        currentUserId={ME}
      />,
    )

    expect(container.querySelectorAll('.tick')).toHaveLength(3)
    expect(animTicks(container)).toHaveLength(0)
  })
})

describe('MessageList tickStamp: ✓ → ✓✓ flip of a displayed row', () => {
  it('animates only the rows whose status flipped, not the already read ones', () => {
    const messages = [incoming('in-1', 1), outgoing('out-1', 2), outgoing('out-2', 3)]
    const { container, rerender } = render(
      <MessageList messages={messages} currentUserId={ME} peerReadUpToSeq={2} />,
    )
    expect(container.querySelector('.tick.read')).not.toHaveClass('anim')

    rerender(<MessageList messages={messages} currentUserId={ME} peerReadUpToSeq={3} />)

    // out-2 crossed the watermark — its fresh ✓✓ stamp plays tickStamp;
    // out-1 was read BEFORE this render — its stamp stays settled;
    // the incoming row has no stamp at all.
    const read = container.querySelectorAll<HTMLElement>('.tick.read')
    expect(read).toHaveLength(2)
    expect(read[0]).not.toHaveClass('anim')
    expect(read[1]).toHaveClass('anim')
    expect(container.querySelectorAll('.tick.dlv')).toHaveLength(0)
    expect(animTicks(container)).toHaveLength(1)
  })

  it('keeps the played stamp marker and never re-animates on unrelated re-renders', () => {
    const messages = [outgoing('out-1', 2), outgoing('out-2', 3)]
    const { container, rerender } = render(<MessageList messages={messages} currentUserId={ME} />)
    rerender(<MessageList messages={messages} currentUserId={ME} peerReadUpToSeq={3} />)
    expect(animTicks(container)).toHaveLength(2)

    // An appended incoming message re-renders the list — the played
    // stamps keep their (spent) marker, no NEW animation starts.
    rerender(
      <MessageList
        messages={[...messages, incoming('in-9', 9)]}
        currentUserId={ME}
        peerReadUpToSeq={3}
      />,
    )

    expect(container.querySelectorAll('.message')).toHaveLength(3)
    expect(animTicks(container)).toHaveLength(2)
    const read = container.querySelectorAll<HTMLElement>('.tick.read')
    expect(read).toHaveLength(2)
    expect(read[0]).toHaveClass('anim')
    expect(read[1]).toHaveClass('anim')
  })

  it('animates the group ✓✓ flip driven by othersReadUpToSeq the same way', () => {
    const messages = [outgoing('out-1', 2)]
    const members = [groupMember(ME, 'me'), groupMember(PEER, 'peer')]
    const { container, rerender } = render(
      <MessageList messages={messages} currentUserId={ME} members={members} />,
    )
    expect(container.querySelector('.tick.dlv')).not.toHaveClass('anim')

    rerender(
      <MessageList
        messages={messages}
        currentUserId={ME}
        members={members}
        othersReadUpToSeq={2}
      />,
    )

    expect(container.querySelector('.tick.read')).toHaveClass('anim')
    expect(animTicks(container)).toHaveLength(1)
  })
})

describe('MessageList tickStamp: «телеграф отбил» — local row becomes a server ✓', () => {
  it('animates the fresh ✓ stamp of a message whose optimistic row was displayed', () => {
    const { container, rerender } = render(
      <MessageList
        messages={[]}
        currentUserId={ME}
        pending={[{ clientMessageId: 'cm-1', text: 'Летит' }]}
      />,
    )
    expect(screen.getByText('отправляется')).toBeVisible()
    expect(animTicks(container)).toHaveLength(0)

    rerender(<MessageList messages={[outgoing('cm-1', 2)]} currentUserId={ME} />)

    // The ack replaced the optimistic row — the stamp event plays.
    const tick = container.querySelector<HTMLElement>('.tick.dlv')
    expect(tick).not.toBeNull()
    expect(tick).toHaveClass('anim')
    expect(screen.queryByText('отправляется')).toBeNull()
  })

  it('keeps the played ✓ marker on later re-renders (the animation is spent, not replayed)', () => {
    const { container, rerender } = render(
      <MessageList
        messages={[]}
        currentUserId={ME}
        pending={[{ clientMessageId: 'cm-1', text: 'Летит' }]}
      />,
    )
    rerender(<MessageList messages={[outgoing('cm-1', 2)]} currentUserId={ME} />)
    rerender(
      <MessageList messages={[outgoing('cm-1', 2), incoming('in-9', 9)]} currentUserId={ME} />,
    )

    expect(container.querySelector('.tick.dlv')).toHaveClass('anim')
    expect(animTicks(container)).toHaveLength(1)
  })

  it('does not animate a server message that was never shown as a local row', () => {
    const { container } = render(
      <MessageList
        messages={[outgoing('m-1', 1)]}
        currentUserId={ME}
        pending={[{ clientMessageId: 'cm-2', text: 'Летит' }]}
      />,
    )

    expect(container.querySelector('.tick.dlv')).not.toHaveClass('anim')
    expect(animTicks(container)).toHaveLength(0)
  })
})

describe('MessageList mass watermark jump (edge case: no re-render avalanche)', () => {
  it('re-renders exactly the flipped rows once — untouched rows render zero extra times', () => {
    const messages = [
      ...Array.from({ length: 12 }, (_, index) => outgoing(`out-${index + 1}`, index + 1)),
      incoming('in-0', 13),
    ]
    const { container, rerender } = render(<MessageList messages={messages} currentUserId={ME} />)
    // 13 rows painted once (12 outgoing + 1 incoming), all ✓.
    expect(stamps.avatarRenders).toBe(13)
    expect(container.querySelectorAll('.tick.dlv')).toHaveLength(12)

    // The peer read EVERYTHING up to seq 7 in one frame: rows 1–7
    // flip ✓→✓✓, rows 8–12 and the incoming row must not re-render.
    rerender(<MessageList messages={messages} currentUserId={ME} peerReadUpToSeq={7} />)

    expect(container.querySelectorAll('.tick.read')).toHaveLength(7)
    expect(container.querySelectorAll('.tick.read.anim')).toHaveLength(7)
    const dlv = container.querySelectorAll<HTMLElement>('.tick.dlv')
    expect(dlv).toHaveLength(5)
    dlv.forEach((tick) => {
      expect(tick).not.toHaveClass('anim')
    })
    // 7 flipped rows × exactly one render — and nothing else.
    expect(stamps.avatarRenders).toBe(13 + 7)
  })
})
