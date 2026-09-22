import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import type { Message } from '../../../api/chats'
import { MessageList } from '../MessageList'

const ME = '11111111-1111-1111-1111-111111111111'
const PEER = '22222222-2222-2222-2222-222222222222'

function message(overrides: Partial<Message> = {}): Message {
  return {
    id: 'm-1',
    chatId: 'chat-1',
    senderId: ME,
    text: 'Привет',
    seq: 1,
    createdAt: '2026-09-20T12:00:00.123Z',
    ...overrides,
  }
}

function renderedTexts(container: HTMLElement): string[] {
  return Array.from(container.querySelectorAll('.message')).map(
    (item) => item.querySelector('.message-text')?.textContent ?? '',
  )
}

afterEach(cleanup)

describe('MessageList delivery statuses', () => {
  it('renders outgoing server messages as delivered with a single check mark', () => {
    const { container } = render(
      <MessageList messages={[message({ senderId: ME })]} currentUserId={ME} />,
    )

    expect(screen.getByText('Привет')).toBeVisible()
    expect(screen.getByText('доставлено ✓')).toBeVisible()
    expect(container.querySelectorAll('.message.outgoing')).toHaveLength(1)
  })

  it('renders incoming messages without any status marks', () => {
    const { container } = render(
      <MessageList messages={[message({ senderId: PEER, text: 'Ответ' })]} currentUserId={ME} />,
    )

    expect(screen.getByText('Ответ')).toBeVisible()
    expect(screen.queryByText(/доставлено/)).toBeNull()
    expect(screen.queryByText(/отправляется/)).toBeNull()
    expect(container.querySelector('.message-status')).toBeNull()
    expect(container.querySelectorAll('.message.incoming')).toHaveLength(1)
  })

  it('renders pending optimistic entries as sending', () => {
    const { container } = render(
      <MessageList
        messages={[]}
        currentUserId={ME}
        pending={[{ clientMessageId: 'cm-1', text: 'Ещё летит' }]}
      />,
    )

    expect(screen.getByText('Ещё летит')).toBeVisible()
    expect(screen.getByText('отправляется')).toBeVisible()
    expect(screen.queryByText(/доставлено/)).toBeNull()
    expect(container.querySelectorAll('.message.outgoing')).toHaveLength(1)
  })
})

describe('MessageList idempotent render', () => {
  it('prefers the server copy when the optimistic entry is already acknowledged', () => {
    const { container } = render(
      <MessageList
        messages={[message({ id: 'cm-1', senderId: ME, text: 'Подтверждено' })]}
        currentUserId={ME}
        pending={[{ clientMessageId: 'cm-1', text: 'Подтверждено' }]}
      />,
    )

    expect(container.querySelectorAll('.message')).toHaveLength(1)
    expect(screen.getByText('Подтверждено')).toBeVisible()
    expect(screen.getByText('доставлено ✓')).toBeVisible()
    expect(screen.queryByText('отправляется')).toBeNull()
  })

  it('renders server messages and pending entries together in order', () => {
    const { container } = render(
      <MessageList
        messages={[
          message({ id: 'm-1', senderId: PEER, text: 'Раз', seq: 1 }),
          message({ id: 'm-2', senderId: ME, text: 'Два', seq: 2 }),
        ]}
        currentUserId={ME}
        pending={[{ clientMessageId: 'cm-3', text: 'Три' }]}
      />,
    )

    expect(renderedTexts(container)).toEqual(['Раз', 'Два', 'Три'])
    expect(container.querySelectorAll('.message.incoming')).toHaveLength(1)
    expect(container.querySelectorAll('.message.outgoing')).toHaveLength(2)
  })

  it('shows the empty state when there is nothing to render', () => {
    render(<MessageList messages={[]} currentUserId={ME} />)

    expect(screen.getByText('Сообщений пока нет')).toBeVisible()
  })
})
