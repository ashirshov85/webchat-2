import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
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
 *
 * T048 (US4, FR-030/FR-034): the 004/005 expectations run unchanged
 * on the reskinned DOM — the scroll container keeps the 004
 * `message-list` hook AND the prototype `chat-scroll` surface (golden
 * scrollbars, machine.css) on one element, and the empty state rides
 * the `.messenger-empty` design-system slot (FR-032 family), so the
 * US4 restyling passes cannot move pagination off the feed surface.
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
    // T048: the 004 hook rides with the prototype scroll surface —
    // one element serves both contracts (research §C, FR-034).
    expect(list).toHaveClass('chat-scroll')
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
    // T048: the empty state rides the design-system slot (FR-032).
    expect(container.querySelector('.messenger-empty')?.textContent).toBe('Сообщений пока нет')
    expect(container.querySelector('.message-list')).toBeNull()
    expect(container.querySelector('.message')).toBeNull()
    expect(screen.queryByText('Загрузка истории…')).toBeNull()
    expect(onLoadOlder).not.toHaveBeenCalled()
  })
})

describe('MessageList empty chat state pins the composer to the bottom (bug 10 / T088)', () => {
  /**
   * The feed variant of `.messenger-empty` («Сообщений пока нет» from
   * MessageList, «Чат не выбран» from MessengerPage) is a DIRECT child
   * of the `.chat` flex column (messenger.css), so it must GROW
   * (`flex: 1`) and carry the exact feed surface of
   * `.message-list.chat-scroll` (message-list.css: #140f08, the brass
   * radial light from the top, the darkening overlay, the --pat-msg
   * ornament at 240px) — then the composer (`flex: none`) stays
   * pinned to the panel bottom instead of hugging the placeholder
   * (bug 10). The placeholder itself is NOT vertically centered: it
   * keeps the FR-032 typography/paddings at the TOP of the grown
   * element; the sidebar instances (`.chat-panel .messenger-empty`,
   * hint padding 10px) are out of the scope and stay untouched.
   *
   * jsdom does not cascade stylesheets, so the contract is pinned
   * statically at its source of truth — the stylesheet text (the
   * T083 pattern).
   */
  const statesCss = readFileSync(join(import.meta.dirname, '../states.css'), 'utf8')
  const feedCss = readFileSync(join(import.meta.dirname, '../message-list.css'), 'utf8')

  /** Extracts the declarations block of a rule whose selector starts a line. */
  function ruleBody(css: string, selector: string): string {
    const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
    const match = css.match(new RegExp(`(^|\\n)${escaped}\\s*\\{([^}]*)\\}`))
    if (!match) throw new Error(`rule not found: ${selector}`)
    return match[2] ?? ''
  }

  /** Extracts a single declaration value, whitespace-normalised. */
  function declaration(body: string, property: string): string {
    const match = body.match(new RegExp(`${property}:\\s*([^;]+);`))
    if (!match) throw new Error(`declaration not found: ${property}`)
    return (match[1] ?? '').replace(/\s+/g, ' ').trim()
  }

  it('the feed variant grows and carries the exact feed surface of .message-list.chat-scroll', () => {
    const emptyFeed = ruleBody(statesCss, '.chat > .messenger-empty')

    expect(emptyFeed).toContain('flex: 1')
    expect(emptyFeed).toContain('min-height: 0')

    const feed = ruleBody(feedCss, '.message-list.chat-scroll')
    expect(declaration(emptyFeed, 'background-color')).toBe(declaration(feed, 'background-color'))
    expect(declaration(emptyFeed, 'background-image')).toBe(declaration(feed, 'background-image'))
    expect(declaration(emptyFeed, 'background-size')).toBe(declaration(feed, 'background-size'))
    expect(declaration(emptyFeed, 'background-image')).toContain('var(--pat-msg)')
    expect(declaration(emptyFeed, 'background-size')).toContain('240px 240px')
  })

  it('the placeholder stays top-pinned (no vertical centering); sidebar instances keep their hint scale', () => {
    const emptyFeed = ruleBody(statesCss, '.chat > .messenger-empty')

    expect(emptyFeed).not.toMatch(/display:\s*flex/)
    expect(emptyFeed).not.toContain('align-items')
    expect(emptyFeed).not.toContain('justify-content')

    const base = ruleBody(statesCss, '.messenger-empty')
    expect(base).toContain('padding: 24px 12px')
    expect(base).toContain('font: italic 12.5px var(--font-body)')

    const sidebar = ruleBody(statesCss, '.chat-panel .messenger-empty')
    expect(sidebar).toContain('padding: 10px')
    expect(sidebar).not.toContain('flex')
    expect(sidebar).not.toContain('background')
  })
})
