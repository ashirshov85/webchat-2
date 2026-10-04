import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { Message } from '../../../api/chats'
import type { OutboxRecord } from '../../outbox'
import { MessageList } from '../MessageList'

/**
 * Outbox states on the reskinned feed (feature 008, US4, T048;
 * FR-030/FR-034): the 005 behavioral expectations — the SC-002 status
 * texts «отправляется»/«не отправлено (переполнение очереди)», the
 * «Повторить»/«Удалить» actions addressed by the SAME clientMessageId,
 * the server-copy convergence — run unchanged; the adaptation pins
 * them onto the PROTOTYPE markup (research §C): every local row rides
 * `.msg.me` + `.bubble` (`.b-text` body, `.b-time` footer hosting the
 * status and the actions), so the T050 design-system pass restyles
 * the states without ever leaving the bubble surface.
 */

const ME = '11111111-1111-1111-1111-111111111111'
const PEER = '22222222-2222-2222-2222-222222222222'
const CHAT = 'chat-1'

function message(overrides: Partial<Message> = {}): Message {
  return {
    id: 'm-1',
    chatId: CHAT,
    senderId: ME,
    text: 'Привет',
    seq: 1,
    createdAt: '2026-09-20T12:00:00.123Z',
    ...overrides,
  }
}

function outboxRecord(overrides: Partial<OutboxRecord> = {}): OutboxRecord {
  return {
    clientMessageId: 'cm-1',
    chatId: CHAT,
    text: 'Не ушло',
    state: 'failed',
    errorCode: 'you_are_blocked',
    ...overrides,
  }
}

function renderedTexts(container: HTMLElement): string[] {
  return Array.from(container.querySelectorAll('.message')).map(
    (item) => item.querySelector('.message-text')?.textContent ?? '',
  )
}

afterEach(cleanup)

describe('MessageList outbox rendering', () => {
  it('renders a sending outbox record as sending after server messages and without action buttons', () => {
    const { container } = render(
      <MessageList
        messages={[message({ id: 'm-1', senderId: PEER, text: 'Раз', seq: 1 })]}
        currentUserId={ME}
        outbox={[
          outboxRecord({
            clientMessageId: 'cm-2',
            text: 'Летит',
            state: 'sending',
            retryAt: Date.now() + 5000,
          }),
        ]}
      />,
    )

    expect(renderedTexts(container)).toEqual(['Раз', 'Летит'])
    expect(container.querySelectorAll('.message.outgoing')).toHaveLength(1)
    // T048: the optimistic row rides the prototype bubble markup — the
    // status lives in the `.b-time` footer of its own bubble, and a
    // local row never carries a delivery tick (FR-030 surface for T050).
    const sendingRow = container.querySelector('.message.outgoing') as HTMLElement
    expect(sendingRow).toHaveClass('msg', 'me')
    expect(sendingRow.querySelector('.bubble .b-text')?.textContent).toBe('Летит')
    expect(sendingRow.querySelector('.b-time .message-status')?.textContent).toBe('отправляется')
    expect(sendingRow.querySelector('.tick')).toBeNull()
    expect(screen.getByText('отправляется')).toBeVisible()
    expect(screen.queryByText(/не отправлено/)).toBeNull()
    expect(screen.queryByRole('button', { name: 'Повторить' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Удалить' })).toBeNull()
  })

  it('renders a failed outbox record as not sent with retry and delete buttons', () => {
    const { container } = render(
      <MessageList messages={[]} currentUserId={ME} outbox={[outboxRecord()]} />,
    )

    expect(screen.getByText('Не ушло')).toBeVisible()
    expect(screen.getByText('не отправлено')).toBeVisible()
    expect(screen.queryByText(/отправляется/)).toBeNull()
    expect(screen.getByRole('button', { name: 'Повторить' })).toBeVisible()
    expect(screen.getByRole('button', { name: 'Удалить' })).toBeVisible()
    expect(container.querySelectorAll('.message.outgoing')).toHaveLength(1)
    // T048: the failure state stays ON the bubble — the reason and the
    // manual actions sit in its `.b-time` footer with their 005 hook
    // classes intact (the design-system surface T050 must keep).
    const failedRow = container.querySelector('.message.outgoing') as HTMLElement
    expect(failedRow).toHaveClass('msg', 'me')
    expect(failedRow.querySelector('.bubble .b-text')?.textContent).toBe('Не ушло')
    expect(failedRow.querySelector('.b-time .message-status-failed')?.textContent).toBe(
      'не отправлено',
    )
    expect(failedRow.querySelector('.b-time .message-retry')).toBeInstanceOf(HTMLButtonElement)
    expect(failedRow.querySelector('.b-time .message-remove')).toBeInstanceOf(HTMLButtonElement)
  })
})

describe('MessageList failed-message actions', () => {
  it('retries manually with the same clientMessageId when the retry button is clicked', () => {
    const onRetry = vi.fn()
    render(
      <MessageList
        messages={[]}
        currentUserId={ME}
        outbox={[outboxRecord({ clientMessageId: 'cm-fixed-id' })]}
        onRetry={onRetry}
      />,
    )

    fireEvent.click(screen.getByRole('button', { name: 'Повторить' }))

    expect(onRetry).toHaveBeenCalledTimes(1)
    expect(onRetry).toHaveBeenCalledWith('cm-fixed-id')
  })

  it('deletes the record locally with the same clientMessageId when the delete button is clicked', () => {
    const onRemove = vi.fn()
    render(
      <MessageList
        messages={[]}
        currentUserId={ME}
        outbox={[outboxRecord({ clientMessageId: 'cm-fixed-id' })]}
        onRemove={onRemove}
      />,
    )

    fireEvent.click(screen.getByRole('button', { name: 'Удалить' }))

    expect(onRemove).toHaveBeenCalledTimes(1)
    expect(onRemove).toHaveBeenCalledWith('cm-fixed-id')
  })

  it('targets the buttons of their own record when several messages failed', () => {
    const onRetry = vi.fn()
    const onRemove = vi.fn()
    const { container } = render(
      <MessageList
        messages={[]}
        currentUserId={ME}
        outbox={[
          outboxRecord({ clientMessageId: 'cm-a', text: 'Первое' }),
          outboxRecord({ clientMessageId: 'cm-b', text: 'Второе' }),
        ]}
        onRetry={onRetry}
        onRemove={onRemove}
      />,
    )

    expect(container.querySelectorAll('.message')).toHaveLength(2)
    // Both failures render as prototype bubbles of their own (T048).
    expect(container.querySelectorAll('.msg.me .bubble')).toHaveLength(2)

    const retryButtons = screen.getAllByRole('button', { name: 'Повторить' })
    const deleteButtons = screen.getAllByRole('button', { name: 'Удалить' })
    expect(retryButtons).toHaveLength(2)
    expect(deleteButtons).toHaveLength(2)

    fireEvent.click(retryButtons[1]!)
    expect(onRetry).toHaveBeenCalledTimes(1)
    expect(onRetry).toHaveBeenCalledWith('cm-b')

    fireEvent.click(deleteButtons[0]!)
    expect(onRemove).toHaveBeenCalledTimes(1)
    expect(onRemove).toHaveBeenCalledWith('cm-a')
  })
})

describe('MessageList outbox convergence', () => {
  it('prefers the server copy when the outbox record is already acknowledged (201/200)', () => {
    const { container } = render(
      <MessageList
        messages={[message({ id: 'cm-1', senderId: ME, text: 'Подтверждено', seq: 2 })]}
        currentUserId={ME}
        outbox={[outboxRecord({ clientMessageId: 'cm-1', text: 'Подтверждено' })]}
      />,
    )

    expect(container.querySelectorAll('.message')).toHaveLength(1)
    // The confirmed copy carries the ✓ tick stamp — «Доставлено» rides
    // its title (US1 T023, SC-002).
    expect(container.querySelector('.tick.dlv')).toHaveAttribute('title', 'Доставлено')
    expect(screen.queryByText(/не отправлено/)).toBeNull()
    expect(screen.queryByRole('button', { name: 'Повторить' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Удалить' })).toBeNull()
  })

  it('does not leak statuses or action buttons onto incoming messages', () => {
    render(
      <MessageList
        messages={[message({ id: 'm-1', senderId: PEER, text: 'Ответ' })]}
        currentUserId={ME}
        outbox={[outboxRecord()]}
      />,
    )

    const incoming = screen.getByText('Ответ').closest('li')
    expect(incoming?.querySelector('.message-status')).toBeNull()
    expect(incoming?.querySelector('.tick')).toBeNull()
    expect(incoming?.querySelectorAll('button')).toHaveLength(0)
  })
})

describe('MessageList evicted-record reason (T028, FR-005)', () => {
  it('renders the queue-overflow reason on an evicted record with the manual actions', () => {
    render(
      <MessageList
        messages={[]}
        currentUserId={ME}
        outbox={[outboxRecord({ errorCode: 'queue_overflow' })]}
      />,
    )

    expect(screen.getByText('не отправлено (переполнение очереди)')).toBeVisible()
    expect(screen.queryByText('не отправлено')).toBeNull()
    expect(screen.getByRole('button', { name: 'Повторить' })).toBeVisible()
    expect(screen.getByRole('button', { name: 'Удалить' })).toBeVisible()
  })

  it('keeps the plain «не отправлено» for server-side terminal failures', () => {
    render(
      <MessageList
        messages={[]}
        currentUserId={ME}
        outbox={[
          outboxRecord({ clientMessageId: 'cm-blocked', errorCode: 'you_are_blocked' }),
          outboxRecord({ clientMessageId: 'cm-evicted', errorCode: 'queue_overflow' }),
        ]}
      />,
    )

    expect(screen.getByText('не отправлено')).toBeVisible()
    expect(screen.getByText('не отправлено (переполнение очереди)')).toBeVisible()
  })

  it('does not leak the eviction reason onto sending records', () => {
    render(
      <MessageList
        messages={[]}
        currentUserId={ME}
        outbox={[
          outboxRecord({ clientMessageId: 'cm-live', state: 'sending', errorCode: undefined }),
        ]}
      />,
    )

    expect(screen.getByText('отправляется')).toBeVisible()
    expect(screen.queryByText(/переполнение очереди/)).toBeNull()
  })
})
