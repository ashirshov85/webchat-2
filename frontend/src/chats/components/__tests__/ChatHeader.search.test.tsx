import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ChatHeader } from '../ChatHeader'

/**
 * T068 (008a Phase 11) — кнопка-лупа #btnSearch прототипа (chats.html
 * §7:443) в .ch-btns ПЕРЕД bell-колоколом: клик включает режим
 * псевдо-поиска по открытому чату (кнопка — состояние `.on`, золотая
 * рамка прототипа :156), повторный клик выключает и возвращает фокус
 * композеру (прототип :1571 — exitSearchMode + $('#msgInput').focus()).
 * Заголовок — проекция: фактический режим и фокус принадлежат
 * странице (MessengerPage), focus-visible — конвенция 008 FR-035.
 */

vi.mock('../../../presence/usePresence', () => ({
  usePresenceStatus: () => 'unknown',
  usePresenceEntry: () => undefined,
}))

function directChat() {
  return {
    kind: 'direct' as const,
    peerId: '22222222-2222-2222-2222-222222222222',
    username: 'alice',
    blockedByMe: false,
    peerInContacts: true,
  }
}

function renderHeader(props: Record<string, unknown> = {}) {
  return render(<ChatHeader chat={directChat()} {...props} />)
}

/** Лупа по title прототипа #btnSearch. */
function searchButton(): HTMLElement {
  return screen.getByRole('button', { name: 'Поиск' })
}

afterEach(cleanup)

describe('ChatHeader кнопка-лупа псевдо-поиска (T068, прототип #btnSearch)', () => {
  it('стоит в .ch-btns ПЕРВЫМ — перед bell-колоколом и шестернёнкой; SVG-лупа прототипа', () => {
    const { container } = renderHeader()

    const buttons = container.querySelector('.chat-head .ch-btns')
    expect(buttons).not.toBeNull()
    const children = Array.from(buttons?.children ?? [])
    expect(children[0]).toBe(searchButton())
    expect(children[1]).toBe(screen.getByRole('button', { name: 'Звуковые оповещения' }))
    expect(children[2]).toBe(screen.getByRole('button', { name: 'Настройки чата' }))
    // прототип :443-445: circle 10.5 + ручка 15.5→21
    expect(searchButton().querySelector('svg circle')).toBeInstanceOf(SVGElement)
  })

  it('режим выкл (умолчание): без .on, aria-pressed=false; клик — onToggleSearch()', () => {
    const onToggleSearch = vi.fn()
    renderHeader({ onToggleSearch })

    expect(searchButton()).not.toHaveClass('on')
    expect(searchButton()).toHaveAttribute('aria-pressed', 'false')

    fireEvent.click(searchButton())
    expect(onToggleSearch).toHaveBeenCalledTimes(1)
  })

  it('режим вкл (searchActive): класс .on + aria-pressed=true; повторный клик — тот же wire', () => {
    const onToggleSearch = vi.fn()
    renderHeader({ onToggleSearch, searchActive: true })

    expect(searchButton()).toHaveClass('ch-btn', 'on')
    expect(searchButton()).toHaveAttribute('aria-pressed', 'true')

    fireEvent.click(searchButton())
    expect(onToggleSearch).toHaveBeenCalledTimes(1)
  })

  it('.on — золотая рамка прототипа :156 в chat-header.css (цвет/рамка/фон)', () => {
    const headerCss = readFileSync(join(import.meta.dirname, '../chat-header.css'), 'utf8')
    const match = headerCss.match(/\.chat-head \.ch-btn\.on\s*\{([^}]*)\}/)
    expect(match).not.toBeNull()
    expect(match?.[1]).toContain('color: var(--gold-light)')
    expect(match?.[1]).toContain('border-color: var(--gold-light)')
    expect(match?.[1]).toContain('background')
  })
})
