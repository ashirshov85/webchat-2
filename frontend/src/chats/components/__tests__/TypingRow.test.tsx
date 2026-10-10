/**
 * Рендер typing-строки «X печатает…» (feature 008a, US2, T039;
 * FR-006–FR-008, ui-behavior.md §2.2, §5): нормативные тексты
 * агрегации и подавление неизвестных участников.
 *
 * - 1 печатающий — «{имя} печатает…» в `.typing-txt` (aria-live
 *   polite); N > 1 — «{имя первого} и ещё {N-1} печатают…», первым
 *   идёт первый участник входа (агрегация FR-008);
 * - неизвестный `userId` (имя не разрешилось из клиентского кэша
 *   №11/№28) не рендерится И не попадает в счёт N; единственный
 *   неизвестный — строка не рендерится вовсе (YAGNI, §2.2);
 * - имя с HTML-вставкой рендерится текстом (React-инвариант
 *   008 FR-033 — паритет US1 AC5);
 * - облик прототипа: `li.typing-row.msg.them`, аватар участника
 *   (инициалы из отображаемого имени, цвет от `username` — источники
 *   разделены T020/FR-004), три `.tlamp`-точки (aria-hidden — текст
 *   единственный смысловой элемент).
 */
import { cleanup, render } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { colorOf } from '../../../ui/avatar'
import { TypingRow } from '../TypingRow'
import type { TypingParticipant } from '../TypingRow'

const ALICE = '22222222-2222-2222-2222-222222222222'
const BOB = '33333333-3333-3333-3333-333333333333'
const CAROL = '44444444-4444-4444-4444-444444444444'
const STRANGER = '99999999-9999-4999-8999-999999999999'

function typist(userId: string, name?: string, username?: string): TypingParticipant {
  return { userId, name, username }
}

afterEach(() => {
  cleanup()
})

describe('TypingRow: тексты 1/N печатающих (ui-behavior §2.2, §5)', () => {
  it('один печатающий — «{имя} печатает…»', () => {
    const { container } = render(<TypingRow typing={[typist(ALICE, 'Мария', 'alice')]} />)

    expect(container.querySelector('.typing-txt')?.textContent).toBe('Мария печатает…')
  })

  it('двое — «{имя} и ещё 1 печатают…»', () => {
    const { container } = render(
      <TypingRow typing={[typist(ALICE, 'Мария', 'alice'), typist(BOB, 'Борис', 'boris')]} />,
    )

    expect(container.querySelector('.typing-txt')?.textContent).toBe('Мария и ещё 1 печатают…')
  })

  it('трое — «{имя первого} и ещё 2 печатают…»; первым идёт первый участник входа', () => {
    const { container } = render(
      <TypingRow
        typing={[
          typist(BOB, 'Борис', 'boris'),
          typist(ALICE, 'Мария', 'alice'),
          typist(CAROL, 'Анна', 'anna'),
        ]}
      />,
    )

    expect(container.querySelector('.typing-txt')?.textContent).toBe('Борис и ещё 2 печатают…')
  })

  it('имя с HTML-вставкой рендерится текстом — инъекция img/onerror не исполняется (008 FR-033)', () => {
    const evil = '<img src=x onerror=alert(1)>'
    const { container } = render(<TypingRow typing={[typist(ALICE, evil, 'alice')]} />)

    const text = container.querySelector('.typing-txt')
    expect(text?.textContent).toBe(`${evil} печатает…`)
    expect(text?.querySelector('img')).toBeNull()
  })
})

describe('TypingRow: подавление неизвестных userId (ui-behavior §2.2)', () => {
  it('неизвестный участник не рендерится и не попадает в счёт N', () => {
    const { container } = render(
      <TypingRow typing={[typist(ALICE, 'Мария', 'alice'), typist(STRANGER)]} />,
    )

    expect(container.querySelector('.typing-txt')?.textContent).toBe('Мария печатает…')
  })

  it('единственный неизвестный участник — строка не рендерится вовсе', () => {
    const { container } = render(<TypingRow typing={[typist(STRANGER)]} />)

    expect(container.querySelector('.typing-row')).toBeNull()
    expect(container.firstChild).toBeNull()
  })

  it('пустой/пробельное имя — как неизвестное (послабление names.ts)', () => {
    const { container } = render(<TypingRow typing={[typist(ALICE, '   ', 'alice')]} />)

    expect(container.querySelector('.typing-row')).toBeNull()
  })

  it('неизвестный в середине списка не сдвигает первого именованного', () => {
    const { container } = render(
      <TypingRow
        typing={[typist(STRANGER), typist(BOB, 'Борис', 'boris'), typist(ALICE, 'Мария', 'alice')]}
      />,
    )

    expect(container.querySelector('.typing-txt')?.textContent).toBe('Борис и ещё 1 печатают…')
  })

  it('пустой список — строка не рендерится', () => {
    const { container } = render(<TypingRow typing={[]} />)

    expect(container.firstChild).toBeNull()
  })
})

describe('TypingRow: облик по прототипу (ui-behavior §2.2, FR-004/T020)', () => {
  it('разметка: li.typing-row.msg.them, .bubble.typing-b с тремя aria-hidden .tlamp и aria-live текстом', () => {
    const { container } = render(<TypingRow typing={[typist(ALICE, 'Мария', 'alice')]} />)

    expect(container.querySelector('li.typing-row.msg.them')).not.toBeNull()
    expect(container.querySelector('.bubble.typing-b')).not.toBeNull()
    expect(container.querySelectorAll('.tlamp')).toHaveLength(3)
    expect(container.querySelectorAll('.tlamp[aria-hidden="true"]')).toHaveLength(3)
    expect(container.querySelector('.typing-txt')?.getAttribute('aria-live')).toBe('polite')
  })

  it('аватар участника: инициалы из отображаемого имени, цвет — от username (FR-004: переименование не перекрашивает)', () => {
    const { container } = render(<TypingRow typing={[typist(ALICE, 'Мария Иванова', 'alice')]} />)

    expect(container.querySelector('.avatar .av-in span')?.textContent).toBe('МИ')
    expect(container.querySelector<HTMLElement>('.avatar')?.style.getPropertyValue('--av')).toBe(
      colorOf('alice'),
    )
  })

  it('без username цвет деривируется от стабильного userId (фолбэк источника)', () => {
    const { container } = render(<TypingRow typing={[typist(ALICE, 'Мария')]} />)

    expect(container.querySelector<HTMLElement>('.avatar')?.style.getPropertyValue('--av')).toBe(
      colorOf(ALICE),
    )
  })
})
