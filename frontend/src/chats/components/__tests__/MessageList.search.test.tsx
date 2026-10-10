import { cleanup, render } from '@testing-library/react'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import type { Message } from '../../../api/chats'
import { MessageList } from '../MessageList'

/**
 * T068 (008a Phase 11) — прыжок к найденному сообщению и вспышка пузыря
 * (прототип jumpToMessage :1563): клик по строке-результату псевдо-поиска
 * прокручивает ленту к строке сообщения (`scrollIntoView` block:'center')
 * и подсвечивает пузырь золотой вспышкой `.flash` (srFlash ~1.8 с);
 * повторный клик по той же строке ПЕРЕЗАПУСКАЕТ анимацию (remove →
 * reflow → add). Строки ленты несут data-mid — якорь прыжка.
 *
 * jsdom ships no layout and no `scrollIntoView` — the mock records the
 * calls so the tests pin WHICH row the browser would scroll to and with
 * which block (the MessageList seat-suite pattern).
 */

const ME = '11111111-1111-1111-1111-111111111111'
const PEER = '22222222-2222-2222-2222-222222222222'

function dialogMessage(id: string, seq: number, senderId: string, text: string): Message {
  return {
    id,
    chatId: 'chat-1',
    senderId,
    text,
    seq,
    createdAt: '2026-09-20T12:00:00.000Z',
  }
}

const WINDOW: Message[] = [
  dialogMessage('m-1', 1, PEER, 'ночь'),
  dialogMessage('m-2', 2, ME, 'привет'),
  dialogMessage('m-3', 3, PEER, 'привет мир'),
]

let scrolled: Array<{ element: Element; block?: string }>

beforeEach(() => {
  scrolled = []
  Element.prototype.scrollIntoView = function (
    this: Element,
    options?: boolean | ScrollIntoViewOptions,
  ): void {
    scrolled.push({
      element: this,
      block: typeof options === 'object' && options !== null ? options.block : undefined,
    })
  }
})

afterEach(() => {
  Reflect.deleteProperty(Element.prototype, 'scrollIntoView')
  cleanup()
})

/** Строка ленты по data-mid (якорь прыжка прототипа `.msg[data-mid=…]`). */
function rowOf(container: HTMLElement, id: string): HTMLElement {
  const row = container.querySelector(`.message-list li[data-mid="${id}"]`)
  if (!(row instanceof HTMLElement)) {
    throw new Error(`строка ${id} не найдена`)
  }
  return row
}

describe('MessageList прыжок к найденному (T068, прототип jumpToMessage)', () => {
  it('строки серверных сообщений несут data-mid со своим id', () => {
    const { container } = render(<MessageList messages={WINDOW} currentUserId={ME} />)
    expect(rowOf(container, 'm-1')).toBeVisible()
    expect(rowOf(container, 'm-2')).toBeVisible()
    expect(rowOf(container, 'm-3')).toBeVisible()
  })

  it('прыжок: scrollIntoView block:center целевой строки + класс .flash на строке', () => {
    const jump1 = { messageId: 'm-3', requestId: 1 }
    const { container, rerender } = render(
      <MessageList messages={WINDOW} currentUserId={ME} jumpTo={jump1} />,
    )

    expect(scrolled).toHaveLength(1)
    expect(scrolled[0]?.block).toBe('center')
    expect(scrolled[0]?.element).toBe(rowOf(container, 'm-3'))
    expect(rowOf(container, 'm-3').classList.contains('flash')).toBe(true)
    // остальные строки не вспыхивают
    expect(rowOf(container, 'm-1').classList.contains('flash')).toBe(false)

    // тот же объект запроса (посторонний ререндер) — повторного прыжка нет
    rerender(<MessageList messages={WINDOW} currentUserId={ME} jumpTo={jump1} />)
    expect(scrolled).toHaveLength(1)
  })

  it('повторный клик (новый requestId того же сообщения) перезапускает вспышку', () => {
    const { container, rerender } = render(
      <MessageList
        messages={WINDOW}
        currentUserId={ME}
        jumpTo={{ messageId: 'm-2', requestId: 1 }}
      />,
    )
    expect(rowOf(container, 'm-2').classList.contains('flash')).toBe(true)

    // Перезапуск — remove → reflow → add: перехватываем classList DOMTokenList.
    let removed = 0
    let added = 0
    const target = rowOf(container, 'm-2')
    const originalRemove = target.classList.remove.bind(target.classList)
    const originalAdd = target.classList.add.bind(target.classList)
    target.classList.remove = (...tokens: string[]) => {
      if (tokens.includes('flash')) {
        removed += 1
      }
      return originalRemove(...tokens)
    }
    target.classList.add = (...tokens: string[]) => {
      if (tokens.includes('flash')) {
        added += 1
      }
      return originalAdd(...tokens)
    }

    rerender(
      <MessageList
        messages={WINDOW}
        currentUserId={ME}
        jumpTo={{ messageId: 'm-2', requestId: 2 }}
      />,
    )
    expect(removed).toBe(1)
    expect(added).toBe(1)
    expect(target.classList.contains('flash')).toBe(true)
    expect(scrolled.at(-1)?.element).toBe(target)
  })

  it('прыжок к другому сообщению переносит вспышку', () => {
    const { container, rerender } = render(
      <MessageList
        messages={WINDOW}
        currentUserId={ME}
        jumpTo={{ messageId: 'm-1', requestId: 1 }}
      />,
    )
    rerender(
      <MessageList
        messages={WINDOW}
        currentUserId={ME}
        jumpTo={{ messageId: 'm-3', requestId: 2 }}
      />,
    )
    expect(rowOf(container, 'm-1').classList.contains('flash')).toBe(false)
    expect(rowOf(container, 'm-3').classList.contains('flash')).toBe(true)
  })

  it('null/отсутствие jumpTo — без прыжка и вспышки', () => {
    const { container } = render(<MessageList messages={WINDOW} currentUserId={ME} />)
    expect(scrolled).toHaveLength(0)
    expect(container.querySelector('.message-list li.flash')).toBeNull()
  })
})

describe('MessageList CSS вспышки (T068: .flash/srFlash прототипа :153-154)', () => {
  it('.msg.flash .bubble — анимация srFlash 1.8s; keyframes золотого свечения', () => {
    const css = readFileSync(join(import.meta.dirname, '../message-list.css'), 'utf8')
    const rule = css.match(/\.message-list \.msg\.flash \.bubble\s*\{([^}]*)\}/)
    expect(rule).not.toBeNull()
    expect(rule?.[1]).toContain('animation: srFlash 1.8s')

    const frames = css.indexOf('@keyframes srFlash')
    expect(frames).toBeGreaterThan(-1)
    expect(css).toContain('rgba(232, 200, 119, 0.65)')
  })
})

describe('MessageList прыжок без scrollIntoView (guard окружения)', () => {
  it('эффект не падает — вспышка всё равно ставится', () => {
    Reflect.deleteProperty(Element.prototype, 'scrollIntoView')
    const { container } = render(
      <MessageList
        messages={WINDOW}
        currentUserId={ME}
        jumpTo={{ messageId: 'm-3', requestId: 1 }}
      />,
    )
    expect(rowOf(container, 'm-3').classList.contains('flash')).toBe(true)
  })
})
