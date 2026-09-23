import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { Message } from '../../../api/chats'
import { MessageList } from '../MessageList'

/**
 * History pagination of the dialog window (US3, T039/T040, FR-008):
 * scrolling close to the top requests the next older page, the
 * prepended page keeps the viewport anchored, loading stops cleanly at
 * the boundary (empty answer — no errors) and an empty chat renders
 * the plain empty state.
 *
 * jsdom has no layout, so the scroll metrics of the list element are
 * provided explicitly per test.
 */

const ME = '11111111-1111-1111-1111-111111111111'
const PEER = '22222222-2222-2222-2222-222222222222'
const CHAT = 'chat-1'

/** 120-message history fixture: three pages of 50/50/20 (FR-008). */
const HISTORY: Message[] = Array.from({ length: 120 }, (_, index) => ({
  id: `m-${index + 1}`,
  chatId: CHAT,
  senderId: index % 2 === 0 ? ME : PEER,
  text: `Сообщение ${index + 1}`,
  seq: index + 1,
  createdAt: `2026-09-20T12:00:00.${String(index).padStart(3, '0')}Z`,
}))

function renderedTexts(container: HTMLElement): string[] {
  return Array.from(container.querySelectorAll('.message')).map(
    (item) => item.querySelector('.message-text')?.textContent ?? '',
  )
}

interface ScrollMetrics {
  scrollTop: number
  scrollHeight: number
}

/** jsdom-compatible scroll metrics for the list element. */
function installScrollMetrics(element: HTMLElement, metrics: ScrollMetrics): void {
  Object.defineProperty(element, 'scrollTop', {
    configurable: true,
    get: () => metrics.scrollTop,
    set: (value: number) => {
      metrics.scrollTop = value
    },
  })
  Object.defineProperty(element, 'scrollHeight', {
    configurable: true,
    get: () => metrics.scrollHeight,
  })
}

afterEach(cleanup)

describe('MessageList older-page requests', () => {
  it('requests the older page when scrolled within the top threshold and not below it', () => {
    const onLoadOlder = vi.fn()
    const { container } = render(
      <MessageList
        messages={HISTORY.slice(70)}
        currentUserId={ME}
        hasOlder
        loadingOlder={false}
        onLoadOlder={onLoadOlder}
      />,
    )
    const list = container.querySelector('.message-list') as HTMLOListElement
    const metrics: ScrollMetrics = { scrollTop: 200, scrollHeight: 1200 }
    installScrollMetrics(list, metrics)

    // Far from the top (threshold is 48px) — no request.
    fireEvent.scroll(list)
    expect(onLoadOlder).not.toHaveBeenCalled()

    // Close to the top — one request.
    metrics.scrollTop = 48
    fireEvent.scroll(list)
    expect(onLoadOlder).toHaveBeenCalledTimes(1)
  })

  it('renders the loading indicator and does not re-request while a page is in flight', () => {
    const onLoadOlder = vi.fn()
    const { container, rerender } = render(
      <MessageList
        messages={HISTORY.slice(70)}
        currentUserId={ME}
        hasOlder
        loadingOlder={false}
        onLoadOlder={onLoadOlder}
      />,
    )
    const list = container.querySelector('.message-list') as HTMLOListElement
    const metrics: ScrollMetrics = { scrollTop: 0, scrollHeight: 1200 }
    installScrollMetrics(list, metrics)

    fireEvent.scroll(list)
    expect(onLoadOlder).toHaveBeenCalledTimes(1)

    rerender(
      <MessageList
        messages={HISTORY.slice(70)}
        currentUserId={ME}
        hasOlder
        loadingOlder
        onLoadOlder={onLoadOlder}
      />,
    )

    expect(screen.getByText('Загрузка истории…')).toBeVisible()
    expect(container.querySelector('.message-history')?.getAttribute('aria-busy')).toBe('true')

    // Repeated scrolling while the request is in flight is ignored — no duplicate page fetches.
    fireEvent.scroll(list)
    expect(onLoadOlder).toHaveBeenCalledTimes(1)
  })

  it('renders the prepended older page above in ascending order and keeps requesting pages until the beginning', () => {
    const onLoadOlder = vi.fn()
    const props = (loading: boolean, messages: readonly Message[], hasOlder: boolean) => (
      <MessageList
        messages={messages}
        currentUserId={ME}
        hasOlder={hasOlder}
        loadingOlder={loading}
        onLoadOlder={onLoadOlder}
      />
    )
    const { container, rerender } = render(props(false, HISTORY.slice(70), true))
    const list = container.querySelector('.message-list') as HTMLOListElement
    const metrics: ScrollMetrics = { scrollTop: 0, scrollHeight: 1200 }
    installScrollMetrics(list, metrics)

    // First scroll: the middle page (71–120) is rendered, older history exists.
    fireEvent.scroll(list)
    expect(onLoadOlder).toHaveBeenCalledTimes(1)

    // The middle page arrives and is prepended: 21–120 in ascending seq order.
    rerender(props(false, HISTORY.slice(20), true))
    expect(container.querySelectorAll('.message')).toHaveLength(100)
    expect(renderedTexts(container)[0]).toBe('Сообщение 21')
    expect(renderedTexts(container).at(-1)).toBe('Сообщение 120')

    // Second scroll to the top: the oldest page is requested too.
    metrics.scrollTop = 0
    fireEvent.scroll(list)
    expect(onLoadOlder).toHaveBeenCalledTimes(2)

    // The oldest page is prepended: the whole history 1–120 is rendered without gaps or duplicates.
    rerender(props(false, HISTORY, true))
    expect(container.querySelectorAll('.message')).toHaveLength(120)
    expect(renderedTexts(container)).toEqual(HISTORY.map((message) => message.text))
    expect(container.querySelectorAll('.message')[0]?.textContent).toContain('Сообщение 1')
  })

  it('keeps the viewport anchored to the same content after an older page is prepended', () => {
    const onLoadOlder = vi.fn()
    const { container, rerender } = render(
      <MessageList
        messages={HISTORY.slice(70)}
        currentUserId={ME}
        hasOlder
        loadingOlder={false}
        onLoadOlder={onLoadOlder}
      />,
    )
    const list = container.querySelector('.message-list') as HTMLOListElement
    const metrics: ScrollMetrics = { scrollTop: 0, scrollHeight: 600 }
    installScrollMetrics(list, metrics)

    fireEvent.scroll(list)
    expect(onLoadOlder).toHaveBeenCalledTimes(1)

    // The prepended page grows the content above the viewport by 400px —
    // the scroll offset grows by exactly that, so the visible entries stay
    // in place (no visual jump, US3).
    metrics.scrollHeight = 1000
    rerender(
      <MessageList
        messages={HISTORY.slice(20)}
        currentUserId={ME}
        hasOlder
        loadingOlder={false}
        onLoadOlder={onLoadOlder}
      />,
    )

    expect(metrics.scrollTop).toBe(400)
  })
})

describe('MessageList pagination boundary', () => {
  it('stops requesting older pages after an empty boundary answer and shows no errors', () => {
    const onLoadOlder = vi.fn()
    const props = (messages: readonly Message[], hasOlder: boolean, loadingOlder: boolean) => (
      <MessageList
        messages={messages}
        currentUserId={ME}
        hasOlder={hasOlder}
        loadingOlder={loadingOlder}
        onLoadOlder={onLoadOlder}
      />
    )
    const { container, rerender } = render(props(HISTORY.slice(70), true, false))
    const list = container.querySelector('.message-list') as HTMLOListElement
    const metrics: ScrollMetrics = { scrollTop: 0, scrollHeight: 1200 }
    installScrollMetrics(list, metrics)

    fireEvent.scroll(list)
    expect(onLoadOlder).toHaveBeenCalledTimes(1)

    // The page above the rendered window is empty (the boundary answer) and
    // carries no nextBefore: the hook flips hasOlder to false. The rendered
    // window survives intact — no losses, no error UI, no stuck indicator.
    rerender(props(HISTORY.slice(70), false, false))

    expect(container.querySelectorAll('.message')).toHaveLength(50)
    expect(renderedTexts(container)[0]).toBe('Сообщение 71')
    expect(screen.queryByText('Загрузка истории…')).toBeNull()
    expect(screen.queryByText('Сообщений пока нет')).toBeNull()

    // Further scrolling to the top requests nothing — the history is exhausted.
    metrics.scrollTop = 0
    fireEvent.scroll(list)
    fireEvent.scroll(list)
    expect(onLoadOlder).toHaveBeenCalledTimes(1)
  })
})

describe('MessageList empty chat state', () => {
  it('renders the plain empty state for a dialog without messages and requests no history', () => {
    const onLoadOlder = vi.fn()
    const { container } = render(
      <MessageList
        messages={[]}
        currentUserId={ME}
        hasOlder={false}
        loadingOlder={false}
        onLoadOlder={onLoadOlder}
      />,
    )

    expect(screen.getByText('Сообщений пока нет')).toBeVisible()
    expect(container.querySelector('.message-list')).toBeNull()
    expect(container.querySelector('.message')).toBeNull()
    expect(screen.queryByText('Загрузка истории…')).toBeNull()
    expect(onLoadOlder).not.toHaveBeenCalled()
  })
})
