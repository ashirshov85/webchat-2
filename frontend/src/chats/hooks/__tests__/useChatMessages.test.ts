import { act, renderHook, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { listMessages } from '../../../api/chats'
import type { Message, MessagePage } from '../../../api/chats'
import { useChatMessages } from '../useChatMessages'

const sse = vi.hoisted(() => ({ streamUserEvents: vi.fn() }))

vi.mock('../../../api/sse', () => sse)

vi.mock('../../../api/chats', () => ({ listMessages: vi.fn() }))

const mockedListMessages = vi.mocked(listMessages)

interface MockStream {
  onOpen: (() => void) | undefined
  emit(eventType: string, data: string): void
}

function installStream(): MockStream {
  const listeners = new Map<string, (data: string) => void>()
  const mock: MockStream = {
    onOpen: undefined,
    emit(eventType, data) {
      listeners.get(eventType)?.(data)
    },
  }
  sse.streamUserEvents.mockImplementation((options?: { onOpen?: () => void }) => {
    mock.onOpen = options?.onOpen
    return {
      subscribe(eventType: string, listener: (data: string) => void) {
        listeners.set(eventType, listener)
        return () => {
          listeners.delete(eventType)
        }
      },
      close(): void {
        // not asserted here (covered by useRealtime tests)
      },
    }
  })
  return mock
}

function makeMessage(chatId: string, id: string, seq: number, text = `text-${id}`): Message {
  return {
    id,
    chatId,
    senderId: '9a2c-9a2c-9a2c',
    text,
    seq,
    createdAt: `2026-09-20T12:00:${String(seq % 60).padStart(2, '0')}.000Z`,
  }
}

function page(messages: Message[], nextBefore?: number): MessagePage {
  return nextBefore === undefined ? { messages } : { messages, nextBefore }
}

function emitMessageCreated(stream: MockStream, message: Message): void {
  act(() => {
    stream.emit('message.created', JSON.stringify({ chatId: message.chatId, message }))
  })
}

const mounted: Array<{ unmount(): void }> = []

function mountChatMessages(initialChatId: string | null) {
  const rendered = renderHook((chatId: string | null) => useChatMessages(chatId), {
    initialProps: initialChatId,
  })
  mounted.push(rendered)
  return rendered
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

describe('useChatMessages initial load', () => {
  it('loads the latest page and renders it in ascending seq order', async () => {
    installStream()
    const newest = makeMessage('chat-1', 'm-2', 20)
    const oldest = makeMessage('chat-1', 'm-1', 10)
    mockedListMessages.mockResolvedValueOnce(page([newest, oldest], 10))

    const { result } = mountChatMessages('chat-1')

    expect(result.current.status).toBe('loading')
    await waitFor(() => {
      expect(result.current.status).toBe('ready')
    })
    expect(result.current.error).toBeNull()
    expect(result.current.messages.map((m) => m.id)).toEqual(['m-1', 'm-2'])
    expect(mockedListMessages).toHaveBeenCalledWith('chat-1')
  })

  it('exposes the error and keeps messages empty when the history request fails', async () => {
    installStream()
    const problem = { code: 'chat_not_found' }
    mockedListMessages.mockRejectedValueOnce(problem)

    const { result } = mountChatMessages('chat-1')

    await waitFor(() => {
      expect(result.current.status).toBe('error')
    })
    expect(result.current.error).toBe(problem)
    expect(result.current.messages).toEqual([])
  })

  it('does not fetch anything while no chat is open', async () => {
    installStream()

    const { result } = mountChatMessages(null)

    await waitFor(() => {
      expect(mockedListMessages).not.toHaveBeenCalled()
    })
    expect(result.current.status).toBe('ready')
    expect(result.current.messages).toEqual([])
  })
})

describe('useChatMessages realtime appends', () => {
  it('appends a message.created frame of the open chat in seq order', async () => {
    const stream = installStream()
    mockedListMessages.mockResolvedValueOnce(
      page([makeMessage('chat-1', 'm-2', 20), makeMessage('chat-1', 'm-1', 10)], 10),
    )
    const { result } = mountChatMessages('chat-1')
    await waitFor(() => {
      expect(result.current.status).toBe('ready')
    })

    emitMessageCreated(stream, makeMessage('chat-1', 'm-3', 30))

    expect(result.current.messages.map((m) => m.id)).toEqual(['m-1', 'm-2', 'm-3'])
  })

  it('is idempotent for a duplicate frame: same id keeps state and reference', async () => {
    const stream = installStream()
    mockedListMessages.mockResolvedValueOnce(page([makeMessage('chat-1', 'm-1', 10)], 10))
    const { result } = mountChatMessages('chat-1')
    await waitFor(() => {
      expect(result.current.status).toBe('ready')
    })

    emitMessageCreated(stream, makeMessage('chat-1', 'm-2', 20))
    const afterFirst = result.current.messages
    expect(afterFirst.map((m) => m.id)).toEqual(['m-1', 'm-2'])

    emitMessageCreated(stream, makeMessage('chat-1', 'm-2', 20))
    expect(result.current.messages).toBe(afterFirst)
    expect(result.current.messages.map((m) => m.id)).toEqual(['m-1', 'm-2'])
  })

  it('inserts an out-of-order frame at its seq position', async () => {
    const stream = installStream()
    mockedListMessages.mockResolvedValueOnce(
      page([makeMessage('chat-1', 'm-3', 30), makeMessage('chat-1', 'm-1', 10)], 10),
    )
    const { result } = mountChatMessages('chat-1')
    await waitFor(() => {
      expect(result.current.status).toBe('ready')
    })

    emitMessageCreated(stream, makeMessage('chat-1', 'm-2', 20))

    expect(result.current.messages.map((m) => m.seq)).toEqual([10, 20, 30])
  })

  it('ignores frames of other chats', async () => {
    const stream = installStream()
    mockedListMessages.mockResolvedValueOnce(page([makeMessage('chat-1', 'm-1', 10)], 10))
    const { result } = mountChatMessages('chat-1')
    await waitFor(() => {
      expect(result.current.status).toBe('ready')
    })

    emitMessageCreated(stream, makeMessage('chat-2', 'other-1', 99))

    expect(result.current.messages.map((m) => m.id)).toEqual(['m-1'])
  })
})

describe('useChatMessages convergence on SSE (re)connect (FR-009)', () => {
  it('refetches the latest page on onOpen and converges without duplicates or reordering', async () => {
    const stream = installStream()
    mockedListMessages.mockResolvedValueOnce(
      page([makeMessage('chat-1', 'm-2', 20), makeMessage('chat-1', 'm-1', 10)], 10),
    )
    const { result } = mountChatMessages('chat-1')
    await waitFor(() => {
      expect(result.current.status).toBe('ready')
    })
    expect(result.current.messages.map((m) => m.id)).toEqual(['m-1', 'm-2'])

    // The stream dropped and reconnected: the missed m-3 arrives via the refetch,
    // server copies of m-1/m-2 are fresh objects with the same content.
    mockedListMessages.mockResolvedValueOnce(
      page([
        makeMessage('chat-1', 'm-3', 30),
        makeMessage('chat-1', 'm-2', 20),
        makeMessage('chat-1', 'm-1', 10),
      ]),
    )
    act(() => {
      stream.onOpen?.()
    })

    await waitFor(() => {
      expect(mockedListMessages).toHaveBeenCalledTimes(2)
    })
    await waitFor(() => {
      expect(result.current.messages.map((m) => m.id)).toEqual(['m-1', 'm-2', 'm-3'])
    })
    expect(result.current.status).toBe('ready')
  })

  it('refetches again on every subsequent reconnection', async () => {
    const stream = installStream()
    mockedListMessages.mockResolvedValue(page([makeMessage('chat-1', 'm-1', 10)], 10))
    const { result } = mountChatMessages('chat-1')
    await waitFor(() => {
      expect(result.current.status).toBe('ready')
    })

    act(() => {
      stream.onOpen?.()
    })
    await waitFor(() => {
      expect(mockedListMessages).toHaveBeenCalledTimes(2)
    })
    act(() => {
      stream.onOpen?.()
    })

    await waitFor(() => {
      expect(mockedListMessages).toHaveBeenCalledTimes(3)
    })
    expect(result.current.messages.map((m) => m.id)).toEqual(['m-1'])
  })
})

describe('useChatMessages chat switching', () => {
  it('resets and loads the newly opened chat', async () => {
    installStream()
    mockedListMessages
      .mockResolvedValueOnce(page([makeMessage('chat-1', 'a-1', 1)]))
      .mockResolvedValueOnce(page([makeMessage('chat-2', 'b-5', 50)]))
    const rendered = mountChatMessages('chat-1')
    await waitFor(() => {
      expect(rendered.result.current.messages.map((m) => m.id)).toEqual(['a-1'])
    })

    rendered.rerender('chat-2')

    await waitFor(() => {
      expect(rendered.result.current.messages.map((m) => m.id)).toEqual(['b-5'])
    })
  })

  it('ignores a stale response of the previous chat that resolves after the switch', async () => {
    installStream()
    let resolveStale: (value: MessagePage) => void = () => {}
    const stalePromise = new Promise<MessagePage>((resolve) => {
      resolveStale = resolve
    })
    mockedListMessages
      .mockReturnValueOnce(stalePromise)
      .mockResolvedValueOnce(page([makeMessage('chat-2', 'b-5', 50)]))
    const rendered = mountChatMessages('chat-1')

    rendered.rerender('chat-2')
    await waitFor(() => {
      expect(rendered.result.current.messages.map((m) => m.id)).toEqual(['b-5'])
    })

    act(() => {
      resolveStale(page([makeMessage('chat-1', 'a-1', 1)]))
    })

    expect(rendered.result.current.messages.map((m) => m.id)).toEqual(['b-5'])
  })
})
