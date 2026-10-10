import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ChatListPanel } from '../ChatListPanel'
import type { ChatSearchResultRow } from '../ChatListPanel'

/**
 * T068 (008a Phase 11) — режим результатов псевдо-поиска сайдбара
 * (прототип chats.html renderSearchResults :858): кнопка-лупа
 * заголовка переключает список «Чаты» ↔ «Поиск по чату» — поле несёт
 * placeholder/aria-label «Поиск по чату…» (обычный режим — «Поиск
 * чатов»), список рендерит шапку .sr-head «Поиск по чату — {имя чата}»,
 * подсказки .sr-hint пустого запроса («Введите запрос — результаты
 * появятся здесь») и отсутствия совпадений («Ничего не найдено»),
 * строки-результаты .sr-row.contact — аватар отправителя, имя (свои —
 * «Вы»), время сообщения и сниппет ~96 символов вокруг вхождения;
 * клик по строке вызывает переход к сообщению.
 */

function resultRow(overrides: Partial<ChatSearchResultRow> = {}): ChatSearchResultRow {
  return {
    messageId: 'm-2',
    senderName: 'Вы',
    avatarSource: 'me',
    avatarInitials: 'M',
    time: '12:05',
    snippet: '…привет мир…',
    ...overrides,
  }
}

function chatsList() {
  return [
    {
      chatId: 'chat-1',
      peer: {
        id: '22222222-2222-2222-2222-222222222222',
        username: 'alice',
        email: 'alice@example.com',
        status: 'active' as const,
        createdAt: '2026-09-01T00:00:00.000Z',
      },
      lastMessage: null,
      unreadCount: 0,
      blockedByMe: false,
    },
  ]
}

function renderPanel(props: Record<string, unknown> = {}) {
  return render(<ChatListPanel chats={chatsList()} {...props} />)
}

/** Поле поиска сайдбара. */
function searchField(): HTMLElement {
  return screen.getByLabelText('Поиск чатов')
}

afterEach(cleanup)

describe('ChatListPanel обычный режим: поле без изменений (T068 — режимы поля)', () => {
  it('placeholder «Поиск…», aria-label «Поиск чатов»; локальная фильтрация работает', () => {
    renderPanel()
    const field = searchField()
    expect(field.getAttribute('placeholder')).toBe('Поиск…')
    expect(field).toHaveAttribute('aria-label', 'Поиск чатов')
    expect(screen.getByRole('list', { name: 'Список чатов' })).toBeVisible()

    fireEvent.change(field, { target: { value: 'zzz' } })
    expect(screen.getByText('Ничего не найдено')).toBeVisible()
  })
})

describe('ChatListPanel режим поиска (T068, прототип renderSearchResults)', () => {
  function renderSearch(props: Record<string, unknown> = {}) {
    return renderPanel({
      searchMode: true,
      searchQuery: '',
      searchChatName: 'Маша',
      searchResults: [],
      onSearchQueryChange: vi.fn(),
      onJumpToMessage: vi.fn(),
      ...props,
    })
  }

  /** Поле в режиме поиска. */
  function chatSearchField(): HTMLElement {
    return screen.getByLabelText('Поиск по чату…')
  }

  it('поле: placeholder/aria-label «Поиск по чату…»; включение режима фокусирует поле', () => {
    renderSearch()
    const field = chatSearchField()
    expect(field.getAttribute('placeholder')).toBe('Поиск по чату…')
    expect(field).toHaveAttribute('aria-label', 'Поиск по чату…')
    expect(field).toHaveFocus()
  })

  it('шапка .sr-head «Поиск по чату — {имя чата}» (имя по цепочке US1)', () => {
    const { container } = renderSearch({ searchChatName: 'Маша' })
    const head = container.querySelector('.sr-head')
    expect(head?.textContent).toBe('Поиск по чату — Маша')
  })

  it('шапка без открытого чата — «Нет открытого чата» (защитная ветка прототипа :862)', () => {
    const { container } = renderSearch({ searchChatName: null })
    expect(container.querySelector('.sr-head')?.textContent).toBe('Нет открытого чата')
  })

  it('пустой запрос: .sr-hint «Введите запрос — результаты появятся здесь»', () => {
    const { container } = renderSearch({ searchQuery: '' })
    expect(container.querySelector('.sr-hint')?.textContent).toBe(
      'Введите запрос — результаты появятся здесь',
    )
  })

  it('пробельный запрос — как пустой (trim прототипа: filter.trim())', () => {
    const { container } = renderSearch({ searchQuery: '   ' })
    expect(container.querySelector('.sr-hint')?.textContent).toBe(
      'Введите запрос — результаты появятся здесь',
    )
  })

  it('без совпадений: .sr-hint «Ничего не найдено»', () => {
    const { container } = renderSearch({ searchQuery: 'ночь', searchResults: [] })
    expect(container.querySelector('.sr-hint')?.textContent).toBe('Ничего не найдено')
  })

  it('строки-результаты .sr-row: имя/время/сниппет, свои — «Вы», аватар с инициалами', () => {
    const { container } = renderSearch({
      searchQuery: 'привет',
      searchResults: [
        resultRow({ messageId: 'm-1', senderName: 'Вы', snippet: '…тут привет…', time: '12:04' }),
        resultRow({
          messageId: 'm-2',
          senderName: 'Маша',
          avatarSource: 'alice',
          avatarInitials: 'М',
          snippet: 'привет мир',
          time: '12:05',
        }),
      ],
    })

    const rows = Array.from(container.querySelectorAll('.sr-row'))
    expect(rows).toHaveLength(2)
    expect(rows[0]?.querySelector('.c-name')?.textContent).toBe('Вы')
    expect(rows[0]?.querySelector('.c-time')?.textContent).toBe('12:04')
    expect(rows[0]?.querySelector('.c-prev')?.textContent).toBe('…тут привет…')
    expect(rows[1]?.querySelector('.c-name')?.textContent).toBe('Маша')
    expect(rows[1]?.querySelector('.c-prev')?.textContent).toBe('привет мир')
    expect(rows[1]?.querySelector('.avatar .av-in span')?.textContent).toBe('М')
  })

  it('клик по строке вызывает переход (onJumpToMessage с id сообщения); title подсказки', () => {
    const onJumpToMessage = vi.fn()
    const { container } = renderSearch({
      searchQuery: 'привет',
      searchResults: [resultRow({ messageId: 'm-9' })],
      onJumpToMessage,
    })
    const row = container.querySelector('.sr-row') as HTMLElement
    expect(row).toHaveAttribute('title', 'Перейти к сообщению')

    fireEvent.click(row)
    expect(onJumpToMessage).toHaveBeenCalledTimes(1)
    expect(onJumpToMessage).toHaveBeenCalledWith('m-9')
  })

  it('ввод в поле уходит странице (onSearchQueryChange) — поиск живой по вводу', () => {
    const onSearchQueryChange = vi.fn()
    renderSearch({ onSearchQueryChange })
    fireEvent.change(chatSearchField(), { target: { value: 'эфи' } })
    expect(onSearchQueryChange).toHaveBeenCalledWith('эфи')
  })

  it('обычный список чатов в режиме поиска скрыт', () => {
    renderSearch({ searchQuery: 'привет', searchResults: [resultRow()] })
    expect(screen.queryByRole('list', { name: 'Список чатов' })).toBeNull()
    expect(screen.queryByText('alice')).toBeNull()
  })
})

describe('ChatListPanel CSS псевдо-поиска (T068: .sr-head/.sr-hint прототипа :150-152)', () => {
  it('.sr-head — золотой заголовочный регистр прототипа; .sr-hint — курсив-подсказка', () => {
    const css = readFileSync(join(import.meta.dirname, '../chat-list-panel.css'), 'utf8')

    const head = css.match(/\.chat-panel \.sr-head\s*\{([^}]*)\}/)
    expect(head).not.toBeNull()
    expect(head?.[1]).toContain('var(--gold-light)')
    expect(head?.[1]).toContain('text-transform: uppercase')

    const hint = css.match(/\.chat-panel \.sr-hint\s*\{([^}]*)\}/)
    expect(hint).not.toBeNull()
    expect(hint?.[1]).toContain('var(--muted-3)')
    expect(hint?.[1]).toContain('italic')
  })
})
