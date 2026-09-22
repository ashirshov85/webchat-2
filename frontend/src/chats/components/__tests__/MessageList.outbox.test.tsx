import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { Message } from '../../../api/chats'
import type { OutboxRecord } from '../../outbox'
import { MessageList } from '../MessageList'

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
    expect(screen.getByText('доставлено ✓')).toBeVisible()
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
    expect(incoming?.querySelectorAll('button')).toHaveLength(0)
  })
})
