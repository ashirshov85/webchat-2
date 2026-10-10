/**
 * Машина состояний отправки typing-сигналов (feature 008a, US2, T039;
 * FR-006, ui-behavior.md §2.1): композер открытого чата переводит
 * события черновика в эфемерные №41-сигналы (`sendTyping`, T035):
 *
 * - первый символ серии — `start` немедленно; продолжение ввода —
 *   повтор `start` не чаще окна 3 с (считается от последнего
 *   ОТПРАВЛЕННОГО start);
 * - stop-триггеры: отправка сообщения (любой исход, вкл. отклонённую
 *   пустую), очистка поля, молчание 5 с при непустом поле; двойной
 *   триггер (отправка + очистка) не дублирует запрос — локальная
 *   идемпотентность против двойного погашения зеркалит сервер (T030);
 * - после stop по молчанию новый ввод начинает новую серию со `start`;
 * - заблокированный композер (008 FR-022) и отсутствие чата — сигналов
 *   нет вовсе; блокировка активного состояния гасит его БЕЗ stop
 *   (остаток истекает серверным TTL 8 с, FR-007);
 * - сигналы привязаны к композеру чата: смена чата и размонтирование
 *   гасят предыдущее состояние best-effort;
 * - каждый сигнал fire-and-forget: ошибки сети/429 проглатываются без
 *   повторов (FR-009) и не ломают машину.
 *
 * `sendTyping` замокан (№41 не ходит в сеть); таймеры/Date — fake —
 * окна 3 с/5 с проверяются на границах (2999/3000, 4999/5000).
 */
import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { sendTyping } from '../../../api/chats'
import type { TypingAction } from '../../../api/chats'
import { useTyping } from '../useTyping'
import type { UseTypingOptions, UseTypingResult } from '../useTyping'

vi.mock('../../../api/chats', () => ({ sendTyping: vi.fn() }))

const mockedSendTyping = vi.mocked(sendTyping)

const CHAT = '11111111-1111-4111-8111-111111111111'
const OTHER_CHAT = '55555555-5555-4555-8555-555555555555'

const mounted: Array<{ unmount(): void }> = []

function mountTyping(initial: UseTypingOptions = { chatId: CHAT }) {
  const rendered = renderHook(
    ({ chatId, blocked }: UseTypingOptions) => useTyping({ chatId, blocked }),
    {
      initialProps: initial,
    },
  )
  mounted.push(rendered)
  return rendered
}

type Signal = [chatId: string, action: TypingAction]

/** Все ушедшие №41-сигналы в порядке отправления. */
function signals(): Signal[] {
  return mockedSendTyping.mock.calls.map((call) => [call[0], call[1]])
}

/** Событие изменения черновика композера (сырое значение поля). */
function draft(result: { current: UseTypingResult }, value: string): void {
  act(() => {
    result.current.onDraftChange(value)
  })
}

/** Попытка отправки — любой исход, включая отклонённую пустую (§2.1). */
function sendAttempt(result: { current: UseTypingResult }): void {
  act(() => {
    result.current.onSendAttempt()
  })
}

async function advance(ms: number): Promise<void> {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms)
  })
}

beforeEach(() => {
  vi.resetAllMocks()
  // Сигнал fire-and-forget: машина зовёт .catch на промисе sendTyping —
  // дефолт мока обязан возвращать промис, а не undefined.
  mockedSendTyping.mockResolvedValue(undefined)
  vi.useFakeTimers()
})

afterEach(() => {
  for (const rendered of mounted.splice(0)) {
    rendered.unmount()
  }
  vi.useRealTimers()
  vi.resetAllMocks()
})

describe('useTyping: первый символ и окно повтора 3 с (FR-006, §2.1)', () => {
  it('первый символ простаивающего композера отправляет start немедленно и ровно один раз', () => {
    const { result } = mountTyping()

    draft(result, 'П')
    draft(result, 'При')

    expect(signals()).toEqual([[CHAT, 'start']])
  })

  it('продолжение ввода внутри окна 3 с не повторяет start', async () => {
    const { result } = mountTyping()

    draft(result, 'П')
    await advance(1500)
    draft(result, 'При')
    await advance(1499)
    draft(result, 'Прив')

    expect(signals()).toEqual([[CHAT, 'start']])
  })

  it('ввод на границе окна 3 с повторяет start — окно считается от последнего отправленного start', async () => {
    const { result } = mountTyping()

    draft(result, 'П') // start №1 в t=0
    await advance(2999)
    draft(result, 'При') // окно ещё не истекло — без повтора
    await advance(1) // t=3000
    draft(result, 'Прив') // start №2 в t=3000
    await advance(2999)
    draft(result, 'Приве') // от start №2 всё ещё в окне
    await advance(1) // t=6000
    draft(result, 'Привет') // start №3

    expect(signals()).toEqual([
      [CHAT, 'start'],
      [CHAT, 'start'],
      [CHAT, 'start'],
    ])
  })
})

describe('useTyping: молчание 5 с гасит активное состояние (§2.1)', () => {
  it('молчание при непустом поле отправляет stop к дедлайну 5 с — не раньше', async () => {
    const { result } = mountTyping()

    draft(result, 'Привет')
    await advance(4999)
    expect(signals()).toEqual([[CHAT, 'start']])

    await advance(1)
    expect(signals()).toEqual([
      [CHAT, 'start'],
      [CHAT, 'stop'],
    ])
  })

  it('после stop по молчанию новый ввод начинает новую серию со start', async () => {
    const { result } = mountTyping()

    draft(result, 'Привет')
    await advance(5000)
    draft(result, ' и ещё')

    expect(signals()).toEqual([
      [CHAT, 'start'],
      [CHAT, 'stop'],
      [CHAT, 'start'],
    ])
  })

  it('таймер молчания умирает вместе с состоянием: после stop по отправке второй stop не приходит', async () => {
    const { result } = mountTyping()

    draft(result, 'Привет')
    sendAttempt(result)
    await advance(10000)

    expect(signals()).toEqual([
      [CHAT, 'start'],
      [CHAT, 'stop'],
    ])
  })
})

describe('useTyping: stop-триггеры отправки (§2.1)', () => {
  it('отправка сообщения (любой исход) отправляет stop', () => {
    const { result } = mountTyping()
    draft(result, 'Привет')

    sendAttempt(result)

    expect(signals()).toEqual([
      [CHAT, 'start'],
      [CHAT, 'stop'],
    ])
  })

  it('очистка поля отправляет stop', () => {
    const { result } = mountTyping()
    draft(result, 'Привет')

    draft(result, '')

    expect(signals()).toEqual([
      [CHAT, 'start'],
      [CHAT, 'stop'],
    ])
  })

  it('отправка + очистка поля не дублируют stop — идемпотентность против двойного погашения (T033)', () => {
    const { result } = mountTyping()
    draft(result, 'Привет')

    sendAttempt(result)
    draft(result, '')

    expect(signals()).toEqual([
      [CHAT, 'start'],
      [CHAT, 'stop'],
    ])
  })

  it('stop без активного состояния (повторная отправка/очистка простаивающего поля) ничего не шлёт', () => {
    const { result } = mountTyping()

    sendAttempt(result)
    sendAttempt(result)
    draft(result, '')

    expect(signals()).toEqual([])
  })
})

describe('useTyping: заблокированный композер сигналов не шлёт (008 FR-022, §2.1)', () => {
  it('ввод и отправка в заблокированном композере не отправляют ничего', () => {
    const { result } = mountTyping({ chatId: CHAT, blocked: true })

    draft(result, 'Привет')
    sendAttempt(result)

    expect(signals()).toEqual([])
  })

  it('блокировка активного состояния сбрасывает его БЕЗ stop — остаток истекает серверным TTL 8 с (FR-007)', async () => {
    const rendered = mountTyping()
    draft(rendered.result, 'Привет')

    rendered.rerender({ chatId: CHAT, blocked: true })
    await advance(10000)

    expect(signals()).toEqual([[CHAT, 'start']])
  })

  it('после разблокировки ввод начинается заново со start', () => {
    const rendered = mountTyping({ chatId: CHAT, blocked: true })

    rendered.rerender({ chatId: CHAT, blocked: false })
    draft(rendered.result, 'Привет')

    expect(signals()).toEqual([[CHAT, 'start']])
  })
})

describe('useTyping: сигналы привязаны к композеру конкретного чата (§2.1)', () => {
  it('без открытого чата (chatId null) не отправляется ничего', () => {
    const { result } = mountTyping({ chatId: null })

    draft(result, 'Привет')
    sendAttempt(result)

    expect(signals()).toEqual([])
  })

  it('смена чата гасит активное состояние предыдущего best-effort; новый чат начинает со start', () => {
    const rendered = mountTyping()
    draft(rendered.result, 'Привет')

    rendered.rerender({ chatId: OTHER_CHAT, blocked: false })
    draft(rendered.result, 'Нов')

    expect(signals()).toEqual([
      [CHAT, 'start'],
      [CHAT, 'stop'],
      [OTHER_CHAT, 'start'],
    ])
  })

  it('размонтирование с активным состоянием отправляет stop', () => {
    const rendered = mountTyping()
    draft(rendered.result, 'Привет')

    rendered.unmount()

    expect(signals()).toEqual([
      [CHAT, 'start'],
      [CHAT, 'stop'],
    ])
  })

  it('размонтирование погашенного состояния ничего не шлёт', () => {
    const rendered = mountTyping()
    draft(rendered.result, 'Привет')
    sendAttempt(rendered.result)
    mockedSendTyping.mockClear()

    rendered.unmount()

    expect(signals()).toEqual([])
  })
})

describe('useTyping: best-effort — ошибки проглатываются без повторов (FR-009)', () => {
  it('отклонённый сигнал не ломает машину — состояние живёт и гасится по молчанию', async () => {
    mockedSendTyping.mockRejectedValueOnce(new TypeError('offline'))
    const { result } = mountTyping()

    draft(result, 'Привет')
    await advance(5000)

    expect(signals()).toEqual([
      [CHAT, 'start'],
      [CHAT, 'stop'],
    ])
  })
})
