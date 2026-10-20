import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { MockInstance } from 'vitest'
import type { Message } from '../../../api/chats'
import type { ConferenceView } from '../../../conferences/api/conferences'
import { stubLocationAssign } from '../../../test/location'
import { formatTime } from '../../../ui/time'
import { resetInviteProbeCache } from '../conferenceInviteProbe'
import { ConferenceInviteCard } from '../ConferenceInviteCard'
import { MessageList } from '../MessageList'

/**
 * 009, US1, T024 — карточка-приглашение конференции в ленте сообщений
 * (FR-002, api-contract §4): системное сообщение `kind='system'` с
 * `payload.type='conference_invite'` рендерится карточкой с кнопкой
 * «Подключиться»; клик проверяет статус №45 ПЕРЕД переходом —
 * `status='ended'` оставляет «Конференция завершена» (ответ по старой
 * ссылке, Q20=A), живая — переход `/conference/{id}?mode={mode из
 * ответа}`; неизвестные `payload.type` — нейтральный плейсхолдер
 * «Системное сообщение» (OCP, конституция IV), без кнопки.
 *
 * T069 (US5, баг-фикс проводки T024×T059): ЖИВАЯ проекция завершения
 * на карточке — пропс `ended` (tombstone `endedByChat` live-среза
 * useConferenceRealtime, пробрасывается MessengerPage → MessageList →
 * SystemRow → карточка): `true` — терминальная подпись «Конференция
 * завершена» БЕЗ кнопки и БЕЗ запроса №45 (кадр №18 уже доставлен
 * мессенджеру); липкое терминальное состояние — узнав «завершено»,
 * карточка не откатывается (endedByChat хранит только ПОСЛЕДНЮЮ
 * завершённую конференцию чата: ended B вытесняет A из среза, карточка
 * A остаётся завершённой); `false`/`undefined` — текущее поведение
 * без регресса (клик-проверка №45 остаётся страховкой для конференций,
 * завершённых вне живой сессии).
 *
 * T070 (US5, баг-фикс после T069): НОВЕЙШАЯ карточка ленты дополнительно
 * делает ОДИН фоновый №45-чек на монтаж (`probe`, MessageList помечает
 * последнюю карточку `conference_invite` окна; сессионный кэш по
 * conferenceId — не более одного запроса за сессию). Поэтому клик-тесты
 * ниже мокают №45 цепочкой: первый вызов — фоновый чек (по умолчанию
 * active → кнопка остаётся), следующие — клик-проверка. Прямые рендеры
 * карточки (без MessageList) probe не получают — их ассерты «без №45»
 * не меняются. Полный набор T070 — ConferenceInviteTerminal.test.tsx.
 */

const conferences = vi.hoisted(() => ({ getConference: vi.fn() }))

/** Минимальная проекция conferenceErrorCode контракта (errors→код). */
vi.mock('../../../conferences/api/conferences', () => ({
  getConference: conferences.getConference,
  conferenceErrorCode: (value: unknown) => {
    if (typeof value !== 'object' || value === null || !('errors' in value)) {
      return null
    }
    const errors = (value as { errors?: Record<string, readonly string[]> }).errors
    const code = Object.values(errors ?? {}).flat()[0]
    return typeof code === 'string' ? code : null
  },
}))

const ME = '11111111-1111-1111-1111-111111111111'

function systemMessage(payload: unknown, overrides: Partial<Message> = {}): Message {
  return {
    id: 'm-sys-1',
    chatId: 'chat-1',
    senderId: null,
    text: '',
    seq: 1,
    createdAt: '2026-10-17T12:00:00.000Z',
    kind: 'system',
    ...(payload === null ? {} : { payload: payload as Message['payload'] }),
    ...overrides,
  }
}

function invitePayload(mode: 'video' | 'audio' = 'video'): Record<string, unknown> {
  return { type: 'conference_invite', conferenceId: 'c-1', mode }
}

function conferenceView(overrides: Partial<ConferenceView> = {}): ConferenceView {
  return {
    id: 'c-1',
    chatId: 'chat-1',
    status: 'active',
    mode: 'video',
    createdBy: ME,
    createdAt: '2026-10-17T11:59:00.000Z',
    maxParticipants: 16,
    activeParticipants: 1,
    ...overrides,
  }
}

function renderFeed(messages: readonly Message[]): void {
  render(<MessageList messages={messages} currentUserId={ME} meUsername="alice" />)
}

describe('ConferenceInviteCard / системные виджеты ленты (009 T024)', () => {
  let assignSpy: MockInstance<(url: string) => void>

  beforeEach(() => {
    resetInviteProbeCache()
    conferences.getConference.mockReset()
    // T070: дефолт фонового №45-чека новейшей карточки — живая
    // конференция (кнопка остаётся; клик-цепочки ниже переопределяют)
    conferences.getConference.mockResolvedValue(conferenceView())
    assignSpy = stubLocationAssign()
  })

  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it('рендерит карточку-приглашение с кнопкой «Подключиться» (mode=video)', () => {
    renderFeed([systemMessage(invitePayload('video'))])
    expect(screen.getByText('Видеоконференция')).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Подключиться' })).toBeTruthy()
    expect(screen.getByText(formatTime('2026-10-17T12:00:00.000Z'))).toBeTruthy()
  })

  it('заголовок карточки следует mode=audio (стартовые дефолты №44)', () => {
    renderFeed([systemMessage(invitePayload('audio'))])
    expect(screen.getByText('Аудиоконференция')).toBeTruthy()
  })

  it('клик «Подключиться»: №45 → переход /conference/{id}?mode={mode из ответа}', async () => {
    conferences.getConference.mockResolvedValue(conferenceView({ mode: 'audio' }))
    renderFeed([systemMessage(invitePayload('video'))])
    fireEvent.click(screen.getByRole('button', { name: 'Подключиться' }))
    await waitFor(() => {
      expect(assignSpy).toHaveBeenCalledTimes(1)
    })
    expect(assignSpy).toHaveBeenCalledWith('/conference/c-1?mode=audio')
    expect(conferences.getConference).toHaveBeenCalledWith('c-1')
  })

  it('№45 status=ended → «Конференция завершена», без перехода и без кнопки', async () => {
    // T070: первый №45 — фоновый чек новейшей карточки (active, кнопка
    // остаётся), второй — клик-проверка (ended → терминальная подпись)
    conferences.getConference
      .mockResolvedValueOnce(conferenceView({ status: 'active' }))
      .mockResolvedValueOnce(conferenceView({ status: 'ended' }))
    renderFeed([systemMessage(invitePayload())])
    fireEvent.click(screen.getByRole('button', { name: 'Подключиться' }))
    await waitFor(() => {
      expect(screen.getByText('Конференция завершена')).toBeTruthy()
    })
    expect(assignSpy).not.toHaveBeenCalled()
    expect(screen.queryByRole('button')).toBeNull()
  })

  it('транспортный сбой №45 → «Не удалось подключиться», кнопка остаётся для повтора', async () => {
    // T070: первый №45 — фоновый чек (транспорт — тихий, кнопка жива),
    // затем клик-проверка: сбой → баннер, повторный клик → переход
    conferences.getConference
      .mockRejectedValueOnce(new Error('network'))
      .mockRejectedValueOnce(new Error('network'))
    renderFeed([systemMessage(invitePayload())])
    fireEvent.click(screen.getByRole('button', { name: 'Подключиться' }))
    await waitFor(() => {
      expect(screen.getByText('Не удалось подключиться')).toBeTruthy()
    })
    expect(assignSpy).not.toHaveBeenCalled()
    // повторный клик — повторная проверка №45 (дефолт-мок: active) и переход
    fireEvent.click(screen.getByRole('button', { name: 'Подключиться' }))
    await waitFor(() => {
      expect(assignSpy).toHaveBeenCalledWith('/conference/c-1?mode=video')
    })
  })

  it('единый 404 conference_not_found → «Конференция недоступна», без деталей', async () => {
    // T070: первый №45 — фоновый чек (active, кнопка остаётся),
    // клик-проверка — 404-Problem → нейтральная подпись
    conferences.getConference
      .mockResolvedValueOnce(conferenceView({ status: 'active' }))
      .mockRejectedValueOnce({
        status: 404,
        errors: { conference: ['conference_not_found'] },
      })
    renderFeed([systemMessage(invitePayload())])
    fireEvent.click(screen.getByRole('button', { name: 'Подключиться' }))
    await waitFor(() => {
      expect(screen.getByText('Конференция недоступна')).toBeTruthy()
    })
    expect(assignSpy).not.toHaveBeenCalled()
    expect(screen.queryByRole('button')).toBeNull()
  })

  it('неизвестный payload.type — нейтральный плейсхолдер без кнопки (OCP)', () => {
    renderFeed([
      systemMessage({ type: 'future_widget', data: 1 }),
      systemMessage(
        { type: 'conference_invite', conferenceId: 'c-1', mode: 'video' },
        { id: 'm-sys-2', seq: 2 },
      ),
      systemMessage(null, { id: 'm-sys-3', seq: 3, payload: null }),
    ])
    const placeholders = screen.getAllByText('Системное сообщение')
    expect(placeholders).toHaveLength(2)
    // у плейсхолдеров нет действий; единственная кнопка — у карточки
    expect(screen.getAllByRole('button')).toHaveLength(1)
    expect(screen.getByRole('button', { name: 'Подключиться' })).toBeTruthy()
  })

  it('сломанный payload известного type (не-uuid id / битый mode) — плейсхолдер', () => {
    renderFeed([
      systemMessage({ type: 'conference_invite', conferenceId: 7, mode: 'video' }),
      systemMessage(
        { type: 'conference_invite', conferenceId: 'c-1', mode: 'screen' },
        {
          id: 'm-sys-x',
          seq: 2,
        },
      ),
    ])
    expect(screen.getAllByText('Системное сообщение')).toHaveLength(2)
    expect(screen.queryByRole('button')).toBeNull()
  })
})

describe('живая проекция завершения — пропс ended (009 T069)', () => {
  beforeEach(() => {
    resetInviteProbeCache()
    conferences.getConference.mockReset()
    conferences.getConference.mockResolvedValue(conferenceView())
  })

  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it('ended=true → терминальная «Конференция завершена» БЕЗ кнопки и БЕЗ запроса №45', () => {
    render(
      <ConferenceInviteCard
        conferenceId="c-1"
        mode="video"
        createdAt="2026-10-17T12:00:00.000Z"
        ended
      />,
    )
    expect(screen.getByText('Конференция завершена')).toBeTruthy()
    expect(screen.queryByRole('button')).toBeNull()
    expect(conferences.getConference).not.toHaveBeenCalled()
  })

  it('ended не передан / false → текущее поведение: кнопка «Подключиться» на месте', () => {
    const { rerender } = render(
      <ConferenceInviteCard conferenceId="c-1" mode="video" createdAt="2026-10-17T12:00:00.000Z" />,
    )
    expect(screen.getByRole('button', { name: 'Подключиться' })).toBeTruthy()
    rerender(
      <ConferenceInviteCard
        conferenceId="c-1"
        mode="video"
        createdAt="2026-10-17T12:00:00.000Z"
        ended={false}
      />,
    )
    expect(screen.getByRole('button', { name: 'Подключиться' })).toBeTruthy()
    expect(screen.queryByText('Конференция завершена')).toBeNull()
  })

  it('ended=false без регресса: клик-проверка №45 остаётся — переход по живой конференции', async () => {
    const assignSpy = stubLocationAssign()
    conferences.getConference.mockResolvedValue(conferenceView())
    render(
      <ConferenceInviteCard
        conferenceId="c-1"
        mode="video"
        createdAt="2026-10-17T12:00:00.000Z"
        ended={false}
      />,
    )
    fireEvent.click(screen.getByRole('button', { name: 'Подключиться' }))
    await waitFor(() => {
      expect(assignSpy).toHaveBeenCalledWith('/conference/c-1?mode=video')
    })
  })

  it('липкое терминальное состояние: ended true → false не возвращает кнопку (ended B вытеснил A из среза)', () => {
    const { rerender } = render(
      <ConferenceInviteCard
        conferenceId="c-1"
        mode="video"
        createdAt="2026-10-17T12:00:00.000Z"
        ended
      />,
    )
    expect(screen.getByText('Конференция завершена')).toBeTruthy()
    rerender(
      <ConferenceInviteCard
        conferenceId="c-1"
        mode="video"
        createdAt="2026-10-17T12:00:00.000Z"
        ended={false}
      />,
    )
    expect(screen.getByText('Конференция завершена')).toBeTruthy()
    expect(screen.queryByRole('button')).toBeNull()
  })

  it('проводка MessageList → SystemRow → карточка: endedConferenceId гасит только СВОЮ карточку', () => {
    render(
      <MessageList
        messages={[
          systemMessage({ type: 'conference_invite', conferenceId: 'c-1', mode: 'video' }),
          systemMessage(
            { type: 'conference_invite', conferenceId: 'c-2', mode: 'audio' },
            { id: 'm-sys-2', seq: 2 },
          ),
        ]}
        currentUserId={ME}
        meUsername="alice"
        endedConferenceId="c-1"
      />,
    )
    // карточка c-1 — терминальная (совпала с tombstone endedByChat)
    expect(screen.getByText('Видеоконференция')).toBeTruthy()
    expect(screen.getByText('Конференция завершена')).toBeTruthy()
    // карточка c-2 (другая конференция того же чата) — живая, кнопка остаётся
    expect(screen.getByRole('button', { name: 'Подключиться' })).toBeTruthy()
    expect(screen.getByText('Аудиоконференция')).toBeTruthy()
    expect(screen.getAllByText('Конференция завершена')).toHaveLength(1)
  })
})

describe('длительность на терминальных поверхностях карточки (009 T071)', () => {
  beforeEach(() => {
    resetInviteProbeCache()
    conferences.getConference.mockReset()
    conferences.getConference.mockResolvedValue(conferenceView())
  })

  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it('клик-проверка №45: ended с startedAt/endedAt → «Конференция завершена · 5 мин»', async () => {
    // первый №45 — фоновый чек новейшей (active, кнопка остаётся),
    // второй — клик-проверка: ended с полями → длительность из ответа
    conferences.getConference
      .mockResolvedValueOnce(conferenceView({ status: 'active' }))
      .mockResolvedValueOnce(
        conferenceView({
          status: 'ended',
          startedAt: '2026-10-17T12:00:00.000Z',
          endedAt: '2026-10-17T12:05:00.000Z',
        }),
      )
    renderFeed([systemMessage(invitePayload())])
    fireEvent.click(screen.getByRole('button', { name: 'Подключиться' }))
    await waitFor(() => {
      expect(screen.getByText('Конференция завершена · 5 мин')).toBeTruthy()
    })
    expect(screen.queryByText('Конференция завершена')).toBeNull()
    expect(screen.queryByRole('button')).toBeNull()
  })

  it('деривация T070 (не-новейшая карточка, без №45-ответа) — терминальная подпись БЕЗ длительности', () => {
    conferences.getConference.mockReturnValue(new Promise(() => {}))
    renderFeed([
      systemMessage({ type: 'conference_invite', conferenceId: 'c-old', mode: 'video' }),
      systemMessage(
        { type: 'conference_invite', conferenceId: 'c-1', mode: 'video' },
        { id: 'm-sys-2', seq: 2 },
      ),
    ])
    // старая карточка терминальна чистой деривацией — данных о
    // длительности нет, дополнительных №45 НЕ вводится
    expect(screen.getByText('Конференция завершена')).toBeTruthy()
    expect(screen.queryByText(/Конференция завершена ·/)).toBeNull()
  })

  it('живой кадр №18 (T069): endedSpan с startedAt+endedAt → длительность без №45', () => {
    render(
      <ConferenceInviteCard
        conferenceId="c-1"
        mode="video"
        createdAt="2026-10-17T12:00:00.000Z"
        ended
        endedSpan={{ startedAt: '2026-10-17T12:10:00.000Z', endedAt: '2026-10-17T12:35:00.000Z' }}
      />,
    )
    expect(screen.getByText('Конференция завершена · 25 мин')).toBeTruthy()
    expect(conferences.getConference).not.toHaveBeenCalled()
  })

  it('живой кадр №18 без startedAt (бейджа не было) — подпись БЕЗ длительности', () => {
    render(
      <ConferenceInviteCard
        conferenceId="c-1"
        mode="video"
        createdAt="2026-10-17T12:00:00.000Z"
        ended
        endedSpan={{ endedAt: '2026-10-17T12:35:00.000Z' }}
      />,
    )
    expect(screen.getByText('Конференция завершена')).toBeTruthy()
    expect(screen.queryByText(/Конференция завершена ·/)).toBeNull()
  })

  it('липкая длительность живого пути: ended B вытеснил A из среза — длительность A остаётся', () => {
    const card = (ended: boolean, endedSpan: { startedAt?: string; endedAt?: string } | null) => (
      <ConferenceInviteCard
        conferenceId="c-1"
        mode="video"
        createdAt="2026-10-17T12:00:00.000Z"
        ended={ended}
        endedSpan={endedSpan}
      />
    )
    const { rerender } = render(
      card(true, { startedAt: '2026-10-17T12:10:00.000Z', endedAt: '2026-10-17T12:35:00.000Z' }),
    )
    expect(screen.getByText('Конференция завершена · 25 мин')).toBeTruthy()
    rerender(card(false, null))
    expect(screen.getByText('Конференция завершена · 25 мин')).toBeTruthy()
    expect(screen.queryByRole('button')).toBeNull()
  })
})
