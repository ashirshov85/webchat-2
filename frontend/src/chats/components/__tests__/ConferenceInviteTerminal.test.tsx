import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Message } from '../../../api/chats'
import type { ConferenceView } from '../../../conferences/api/conferences'
import { stubLocationAssign } from '../../../test/location'
import { resetInviteProbeCache } from '../conferenceInviteProbe'
import { MessageList } from '../MessageList'

/**
 * 009, US5, T070 — терминальное состояние карточек конференций,
 * завершённых ВНЕ живой сессии (баг-фикс поверх T069; переиспользует
 * его терминальное состояние/пропс карточки). Пользователь был офлайн
 * в момент завершения / открыл чат позже — кадр №18 не доставлен,
 * локального следа нет, Q20=A не даёт серверного следа в истории →
 * раньше карточка показывала «Подключиться» до ручного клика.
 *
 * Решение БЕЗ нового API (контракт/backend/история не меняются):
 * (1) инвариант «одна активная на чат» (ux_conferences_chat_active,
 * №44 идемпотентен) — в ленте открытого чата все карточки
 * `conference_invite`, КРОМЕ последней по порядку сообщений,
 * гарантированно принадлежат завершённым конференциям → терминальное
 * «Конференция завершена» чисто клиентской деривацией в MessageList
 * (newestInviteConferenceId → пропс ended каждой карточке), БЕЗ
 * запросов; инвариант держит и при пагинации (окно догружается
 * только вверх — последняя карточка окна остаётся новейшей);
 * (2) единственная потенциально живая карточка (последняя) — один
 * №45-чек на открытие чата: `ended` → терминальная подпись;
 * `created`/`active` → кнопка остаётся; Problem
 * `conference_not_found` → нейтральная «Конференция недоступна»;
 * транспортный сбой — тихо оставить кнопку (клик-проверка №45
 * остаётся страховкой), ретрай-цикл НЕ вводится. Мемоизация: не
 * более одного №45 на конференцию за сессию (кэш по conferenceId;
 * уже-терминальная карточка не рефетчится, живая сходится кадрами
 * T069).
 */

const conferences = vi.hoisted(() => ({ getConference: vi.fn() }))

/** Минимальная проекция контракта ошибок (errors→код), как в T024-наборе. */
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

function systemInvite(
  id: string,
  conferenceId: string,
  seq: number,
  mode: 'video' | 'audio' = 'video',
): Message {
  return {
    id,
    chatId: 'chat-1',
    senderId: null,
    text: '',
    seq,
    createdAt: `2026-10-17T12:00:${String(seq % 60).padStart(2, '0')}.000Z`,
    kind: 'system',
    payload: { type: 'conference_invite', conferenceId, mode } as unknown as Message['payload'],
  }
}

function conferenceView(overrides: Partial<ConferenceView> = {}): ConferenceView {
  return {
    id: 'c-new',
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

type ExtraProps = Partial<Parameters<typeof MessageList>[0]>

function renderFeed(messages: readonly Message[], extra: ExtraProps = {}) {
  return render(
    <MessageList messages={messages} currentUserId={ME} meUsername="alice" {...extra} />,
  )
}

/** Строка карточки по data-mid (scope ассертов конкретной карточки). */
function inviteRow(messageId: string): HTMLElement {
  const row = document.querySelector(`li[data-mid="${messageId}"]`)
  if (row === null) {
    throw new Error(`invite row ${messageId} not rendered`)
  }
  return row as HTMLElement
}

describe('деривация «одна активная на чат» — все, кроме последней (009 T070)', () => {
  beforeEach(() => {
    resetInviteProbeCache()
    conferences.getConference.mockReset()
    // висящий запрос новейшей карточки: деривация старых не ждёт ответа
    conferences.getConference.mockReturnValue(new Promise(() => {}))
  })

  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it('≥2 карточки: все, кроме последней, — «Конференция завершена» без кнопки и БЕЗ №45; последняя — с кнопкой', async () => {
    renderFeed([
      systemInvite('m-old', 'c-old', 1, 'audio'),
      systemInvite('m-new', 'c-new', 2, 'video'),
    ])
    // старая конференция завершена чистой деривацией (новая создана
    // только после ended предыдущей) — без запроса по её id
    expect(within(inviteRow('m-old')).getByText('Конференция завершена')).toBeTruthy()
    expect(within(inviteRow('m-old')).queryByRole('button')).toBeNull()
    // последняя (новейшая) карточка — потенциально живая, кнопка на месте
    expect(within(inviteRow('m-new')).getByRole('button', { name: 'Подключиться' })).toBeTruthy()
    expect(within(inviteRow('m-new')).queryByText('Конференция завершена')).toBeNull()
    // №45 ходит ТОЛЬКО по новейшей (фоновый чек), старые id не спрашиваются
    await waitFor(() => {
      expect(conferences.getConference).toHaveBeenCalledTimes(1)
    })
    expect(conferences.getConference).toHaveBeenCalledWith('c-new')
  })

  it('догрузка старой страницы не меняет статус новых карточек и не добавляет №45', async () => {
    const { rerender } = renderFeed([
      systemInvite('m-old', 'c-old', 2, 'audio'),
      systemInvite('m-new', 'c-new', 3, 'video'),
    ])
    await waitFor(() => {
      expect(conferences.getConference).toHaveBeenCalledTimes(1)
    })
    // prepend старой страницы (окно догружается только вверх): древняя
    // карточка терминальна деривацией, статусы прежних не изменились
    rerender(
      <MessageList
        messages={[
          systemInvite('m-ancient', 'c-ancient', 1, 'audio'),
          systemInvite('m-old', 'c-old', 2, 'audio'),
          systemInvite('m-new', 'c-new', 3, 'video'),
        ]}
        currentUserId={ME}
        meUsername="alice"
      />,
    )
    expect(within(inviteRow('m-ancient')).getByText('Конференция завершена')).toBeTruthy()
    expect(within(inviteRow('m-old')).getByText('Конференция завершена')).toBeTruthy()
    expect(within(inviteRow('m-new')).getByRole('button', { name: 'Подключиться' })).toBeTruthy()
    // новейшая не рефетчится повторно (мемоизация инстанса), старые не спрашиваются
    expect(conferences.getConference).toHaveBeenCalledTimes(1)
    expect(conferences.getConference).toHaveBeenCalledWith('c-new')
  })
})

describe('№45-чек новейшей карточки на открытие чата (009 T070)', () => {
  let assignSpy: ReturnType<typeof stubLocationAssign>

  beforeEach(() => {
    resetInviteProbeCache()
    conferences.getConference.mockReset()
    assignSpy = stubLocationAssign()
  })

  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it("status='ended' → терминальная «Конференция завершена» без кнопки и без перехода", async () => {
    conferences.getConference.mockResolvedValue(conferenceView({ status: 'ended' }))
    renderFeed([systemInvite('m-new', 'c-new', 1)])
    await waitFor(() => {
      expect(screen.getByText('Конференция завершена')).toBeTruthy()
    })
    expect(screen.queryByRole('button')).toBeNull()
    expect(assignSpy).not.toHaveBeenCalled()
    expect(conferences.getConference).toHaveBeenCalledTimes(1)
  })

  it("status='active' → кнопка остаётся (живая конференция)", async () => {
    conferences.getConference.mockResolvedValue(conferenceView({ status: 'active' }))
    renderFeed([systemInvite('m-new', 'c-new', 1)])
    await waitFor(() => {
      expect(conferences.getConference).toHaveBeenCalledTimes(1)
    })
    expect(screen.getByRole('button', { name: 'Подключиться' })).toBeTruthy()
    expect(screen.queryByText('Конференция завершена')).toBeNull()
    expect(assignSpy).not.toHaveBeenCalled()
  })

  it("status='created' (никто не вошёл — НЕ завершена) → кнопка остаётся", async () => {
    conferences.getConference.mockResolvedValue(conferenceView({ status: 'created' }))
    renderFeed([systemInvite('m-new', 'c-new', 1)])
    await waitFor(() => {
      expect(conferences.getConference).toHaveBeenCalledTimes(1)
    })
    expect(screen.getByRole('button', { name: 'Подключиться' })).toBeTruthy()
    expect(screen.queryByText('Конференция завершена')).toBeNull()
  })

  it('Problem conference_not_found → нейтральная «Конференция недоступна»', async () => {
    conferences.getConference.mockRejectedValue({
      status: 404,
      errors: { conference: ['conference_not_found'] },
    })
    renderFeed([systemInvite('m-new', 'c-new', 1)])
    await waitFor(() => {
      expect(screen.getByText('Конференция недоступна')).toBeTruthy()
    })
    expect(screen.queryByRole('button')).toBeNull()
    expect(assignSpy).not.toHaveBeenCalled()
  })

  it('транспортный отказ → тихо оставить кнопку, без баннера и без ретрая', async () => {
    conferences.getConference.mockRejectedValue(new Error('network'))
    renderFeed([systemInvite('m-new', 'c-new', 1)])
    await waitFor(() => {
      expect(conferences.getConference).toHaveBeenCalledTimes(1)
    })
    expect(screen.getByRole('button', { name: 'Подключиться' })).toBeTruthy()
    expect(screen.queryByText('Не удалось подключиться')).toBeNull()
    expect(screen.queryByText('Конференция завершена')).toBeNull()
  })
})

describe('мемоизация: не более одного №45 на конференцию за сессию (009 T070)', () => {
  beforeEach(() => {
    resetInviteProbeCache()
    conferences.getConference.mockReset()
  })

  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it('ремоунт ленты (повторное открытие чата) не повторяет №45 уже-терминальной конференции', async () => {
    conferences.getConference.mockResolvedValue(conferenceView({ status: 'ended' }))
    const first = renderFeed([systemInvite('m-new', 'c-new', 1)])
    await waitFor(() => {
      expect(screen.getByText('Конференция завершена')).toBeTruthy()
    })
    expect(conferences.getConference).toHaveBeenCalledTimes(1)
    first.unmount()
    cleanup()
    // повторное открытие: терминальна СРАЗУ из сессионного кэша, без запроса
    renderFeed([systemInvite('m-new', 'c-new', 1)])
    expect(screen.getByText('Конференция завершена')).toBeTruthy()
    expect(screen.queryByRole('button')).toBeNull()
    expect(conferences.getConference).toHaveBeenCalledTimes(1)
  })

  it('ремоунт не повторяет №45 и живой конференции (кэш «live»), кнопка остаётся', async () => {
    conferences.getConference.mockResolvedValue(conferenceView({ status: 'active' }))
    const first = renderFeed([systemInvite('m-new', 'c-new', 1)])
    await waitFor(() => {
      expect(conferences.getConference).toHaveBeenCalledTimes(1)
    })
    first.unmount()
    cleanup()
    renderFeed([systemInvite('m-new', 'c-new', 1)])
    expect(screen.getByRole('button', { name: 'Подключиться' })).toBeTruthy()
    expect(conferences.getConference).toHaveBeenCalledTimes(1)
  })

  it('«Конференция недоступна» переживает ремоунт без повторного №45', async () => {
    conferences.getConference.mockRejectedValue({
      status: 404,
      errors: { conference: ['conference_not_found'] },
    })
    const first = renderFeed([systemInvite('m-new', 'c-new', 1)])
    await waitFor(() => {
      expect(screen.getByText('Конференция недоступна')).toBeTruthy()
    })
    first.unmount()
    cleanup()
    renderFeed([systemInvite('m-new', 'c-new', 1)])
    expect(screen.getByText('Конференция недоступна')).toBeTruthy()
    expect(conferences.getConference).toHaveBeenCalledTimes(1)
  })

  it('другая конференция (новая карточка) — свой единственный №45', async () => {
    conferences.getConference.mockResolvedValue(conferenceView({ status: 'ended' }))
    renderFeed([systemInvite('m-a', 'c-a', 1)])
    await waitFor(() => {
      expect(screen.getByText('Конференция завершена')).toBeTruthy()
    })
    cleanup()
    renderFeed([systemInvite('m-b', 'c-b', 1)])
    await waitFor(() => {
      expect(conferences.getConference).toHaveBeenCalledWith('c-b')
    })
    expect(conferences.getConference).toHaveBeenCalledTimes(2)
  })
})

describe('регресс T069: живая сессия закрывает карточку кадром №18 (009 T070)', () => {
  beforeEach(() => {
    resetInviteProbeCache()
    conferences.getConference.mockReset()
  })

  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it('tombstone endedByChat на монтаже — терминальна БЕЗ фонового №45 (кадр уже доставлен)', () => {
    renderFeed([systemInvite('m-live', 'c-live', 1)], { endedConferenceId: 'c-live' })
    expect(screen.getByText('Конференция завершена')).toBeTruthy()
    expect(screen.queryByRole('button')).toBeNull()
    expect(conferences.getConference).not.toHaveBeenCalled()
  })

  it('деривация и tombstone не конфликтуют: старая — деривация, новейшая — кадр №18', () => {
    renderFeed([systemInvite('m-old', 'c-old', 1), systemInvite('m-live', 'c-live', 2)], {
      endedConferenceId: 'c-live',
    })
    expect(screen.getAllByText('Конференция завершена')).toHaveLength(2)
    expect(screen.queryByRole('button')).toBeNull()
    expect(conferences.getConference).not.toHaveBeenCalled()
  })
})

describe('длительность из №45-ответа новейшей карточки (009 T071)', () => {
  beforeEach(() => {
    resetInviteProbeCache()
    conferences.getConference.mockReset()
  })

  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it('newest-чек №45 ended с startedAt/endedAt → «Конференция завершена · 20 мин»', async () => {
    conferences.getConference.mockResolvedValue(
      conferenceView({
        status: 'ended',
        startedAt: '2026-10-17T12:10:00.000Z',
        endedAt: '2026-10-17T12:30:00.000Z',
      }),
    )
    renderFeed([systemInvite('m-new', 'c-new', 1)])
    await waitFor(() => {
      expect(screen.getByText('Конференция завершена · 20 мин')).toBeTruthy()
    })
    expect(screen.queryByRole('button')).toBeNull()
    expect(conferences.getConference).toHaveBeenCalledTimes(1)
  })

  it('ремоунт восстанавливает длительность из сессионного кэша БЕЗ повторного №45', async () => {
    conferences.getConference.mockResolvedValue(
      conferenceView({
        status: 'ended',
        startedAt: '2026-10-17T12:10:00.000Z',
        endedAt: '2026-10-17T12:30:00.000Z',
      }),
    )
    const first = renderFeed([systemInvite('m-new', 'c-new', 1)])
    await waitFor(() => {
      expect(screen.getByText('Конференция завершена · 20 мин')).toBeTruthy()
    })
    first.unmount()
    cleanup()
    renderFeed([systemInvite('m-new', 'c-new', 1)])
    expect(screen.getByText('Конференция завершена · 20 мин')).toBeTruthy()
    expect(conferences.getConference).toHaveBeenCalledTimes(1)
  })

  it('клик-путь после тихого фонового транспорта: №45 ended с полями → длительность', async () => {
    const assignSpy = stubLocationAssign()
    conferences.getConference.mockRejectedValueOnce(new Error('network')).mockResolvedValueOnce(
      conferenceView({
        status: 'ended',
        startedAt: '2026-10-17T12:00:00.000Z',
        endedAt: '2026-10-17T12:02:00.000Z',
      }),
    )
    renderFeed([systemInvite('m-new', 'c-new', 1)])
    fireEvent.click(screen.getByRole('button', { name: 'Подключиться' }))
    await waitFor(() => {
      expect(screen.getByText('Конференция завершена · 2 мин')).toBeTruthy()
    })
    expect(assignSpy).not.toHaveBeenCalled()
  })

  it('старые карточки (деривация) при длительности новейшей — БЕЗ длительности', async () => {
    conferences.getConference.mockResolvedValue(
      conferenceView({
        status: 'ended',
        startedAt: '2026-10-17T12:10:00.000Z',
        endedAt: '2026-10-17T12:30:00.000Z',
      }),
    )
    renderFeed([systemInvite('m-old', 'c-old', 1), systemInvite('m-new', 'c-new', 2)])
    await waitFor(() => {
      expect(screen.getByText('Конференция завершена · 20 мин')).toBeTruthy()
    })
    // деривация T070 не имеет №45-ответа — подпись без длительности
    expect(screen.getByText('Конференция завершена')).toBeTruthy()
    expect(conferences.getConference).toHaveBeenCalledTimes(1)
    expect(conferences.getConference).toHaveBeenCalledWith('c-new')
  })
})
