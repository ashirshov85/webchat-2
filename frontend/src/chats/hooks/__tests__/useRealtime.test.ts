import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Mock } from 'vitest'
import type { MessageCreatedEvent } from '../../../api/chats'
import { useRealtime } from '../useRealtime'
import type { RealtimeStream, Unsubscribe } from '../useRealtime'

const sse = vi.hoisted(() => ({ streamUserEvents: vi.fn() }))

vi.mock('../../../api/sse', () => sse)

interface MockStream {
  onOpen: (() => void) | undefined
  close: Mock
  emit(eventType: string, data: string): void
}

function installStream(): MockStream {
  const listeners = new Map<string, (data: string) => void>()
  const mock: MockStream = {
    onOpen: undefined,
    close: vi.fn(),
    emit(eventType, data) {
      listeners.get(eventType)?.(data)
    },
  }
  sse.streamUserEvents.mockImplementationOnce((options?: { onOpen?: () => void }) => {
    mock.onOpen = options?.onOpen
    return {
      subscribe(eventType: string, listener: (data: string) => void) {
        listeners.set(eventType, listener)
        return () => {
          listeners.delete(eventType)
        }
      },
      close: mock.close,
    }
  })
  return mock
}

function messageCreatedEvent(chatId: string, messageId: string): MessageCreatedEvent {
  return {
    chatId,
    message: {
      id: messageId,
      chatId,
      senderId: '9a2c-9a2c-9a2c',
      text: 'Привет',
      seq: 128,
      createdAt: '2026-09-20T12:00:00.123Z',
    },
  }
}

const mounted: Array<{ unmount(): void }> = []

function mountRealtime(): { current: RealtimeStream } {
  const rendered = renderHook(() => useRealtime())
  mounted.push(rendered)
  return rendered.result
}

function emitMessageCreated(stream: MockStream, event: MessageCreatedEvent): void {
  act(() => {
    stream.emit('message.created', JSON.stringify(event))
  })
}

beforeEach(() => {
  vi.clearAllMocks()
})

afterEach(() => {
  for (const rendered of mounted.splice(0)) {
    rendered.unmount()
  }
  vi.clearAllMocks()
})

describe('useRealtime connection lifecycle', () => {
  it('opens the user event stream on mount and closes it on unmount', () => {
    const stream = installStream()

    mountRealtime()

    expect(sse.streamUserEvents).toHaveBeenCalledTimes(1)
    expect(stream.close).not.toHaveBeenCalled()

    mounted[0]!.unmount()

    expect(stream.close).toHaveBeenCalledTimes(1)
  })

  it('shares a single stream between concurrent hooks and closes after the last one', () => {
    const stream = installStream()

    mountRealtime()
    mountRealtime()

    expect(sse.streamUserEvents).toHaveBeenCalledTimes(1)

    mounted[0]!.unmount()
    expect(stream.close).not.toHaveBeenCalled()

    mounted[1]!.unmount()
    expect(stream.close).toHaveBeenCalledTimes(1)
  })

  it('opens a fresh stream when remounted after all consumers unmount', () => {
    installStream()
    mountRealtime()
    mounted[0]!.unmount()

    const second = installStream()
    mountRealtime()

    expect(sse.streamUserEvents).toHaveBeenCalledTimes(2)

    second.emit('message.created', 'garbage')
    mounted[1]!.unmount()
    expect(second.close).toHaveBeenCalledTimes(1)
  })
})

describe('useRealtime message.created dispatch', () => {
  it('delivers frames of the matching chat to its listener as parsed events', () => {
    const stream = installStream()
    const listener = vi.fn()
    const { current } = mountRealtime()

    act(() => {
      current.onMessageCreated('chat-1', listener)
    })

    emitMessageCreated(stream, messageCreatedEvent('chat-1', 'm-1'))

    expect(listener).toHaveBeenCalledTimes(1)
    expect(listener).toHaveBeenCalledWith(messageCreatedEvent('chat-1', 'm-1'))
  })

  it('does not deliver other chats frames to a chat-specific listener', () => {
    const stream = installStream()
    const listener = vi.fn()
    const { current } = mountRealtime()

    act(() => {
      current.onMessageCreated('chat-1', listener)
    })

    emitMessageCreated(stream, messageCreatedEvent('chat-2', 'm-2'))

    expect(listener).not.toHaveBeenCalled()
  })

  it('delivers every chat to the wildcard (null) listener, including unknown chats', () => {
    const stream = installStream()
    const listener = vi.fn()
    const { current } = mountRealtime()

    act(() => {
      current.onMessageCreated(null, listener)
    })

    emitMessageCreated(stream, messageCreatedEvent('chat-1', 'm-1'))
    emitMessageCreated(stream, messageCreatedEvent('chat-stranger', 'm-2'))

    expect(listener).toHaveBeenCalledTimes(2)
    expect(listener).toHaveBeenNthCalledWith(1, messageCreatedEvent('chat-1', 'm-1'))
    expect(listener).toHaveBeenNthCalledWith(2, messageCreatedEvent('chat-stranger', 'm-2'))
  })

  it('stops delivery after the returned unsubscribe is called', () => {
    const stream = installStream()
    const listener = vi.fn()
    const { current } = mountRealtime()

    let unsubscribe: Unsubscribe | undefined
    act(() => {
      unsubscribe = current.onMessageCreated('chat-1', listener)
    })
    act(() => {
      unsubscribe?.()
    })

    emitMessageCreated(stream, messageCreatedEvent('chat-1', 'm-1'))

    expect(listener).not.toHaveBeenCalled()
  })

  it('ignores malformed frames without throwing', () => {
    const stream = installStream()
    const listener = vi.fn()
    const { current } = mountRealtime()

    act(() => {
      current.onMessageCreated(null, listener)
    })

    act(() => {
      stream.emit('message.created', 'not json at all')
      stream.emit('message.created', JSON.stringify({ chatId: 'chat-1' }))
      stream.emit(
        'message.created',
        JSON.stringify({ chatId: 7, message: { id: 'm-1', chatId: 'chat-1' } }),
      )
      stream.emit('message.created', JSON.stringify({ chatId: 'chat-1', message: { id: 42 } }))
    })

    expect(listener).not.toHaveBeenCalled()
  })
})

describe('useRealtime onOpen notifications', () => {
  it('notifies listeners on every (re)connection of the stream', () => {
    const stream = installStream()
    const listener = vi.fn()
    const { current } = mountRealtime()

    act(() => {
      current.onOpen(listener)
    })

    act(() => {
      stream.onOpen?.()
      stream.onOpen?.()
    })

    expect(listener).toHaveBeenCalledTimes(2)
  })

  it('stops onOpen notifications after the returned unsubscribe is called', () => {
    const stream = installStream()
    const listener = vi.fn()
    const { current } = mountRealtime()

    let unsubscribe: Unsubscribe | undefined
    act(() => {
      unsubscribe = current.onOpen(listener)
    })
    act(() => {
      unsubscribe?.()
    })

    act(() => {
      stream.onOpen?.()
    })

    expect(listener).not.toHaveBeenCalled()
  })
})
