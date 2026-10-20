import { act, cleanup, renderHook, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ApiProblem } from '../../../api/auth'
import type { ConferenceRealtimeEvent, ConferenceView } from '../../api/conferences'
import {
  applyConferenceEvent,
  applyConferenceView,
  EMPTY_CONFERENCE_SLICE,
  useConferenceRealtime,
} from '../useConferenceRealtime'

/**
 * 009, US5, T061 — useConferenceRealtime (T059, FR-013, US5-1,
 * contracts/realtime-conference-events.md §1/§2/§4): бейдж «звонок
 * идёт» по кадрам №18 `conference.started`/`conference.ended`.
 *
 * Применение — ФУНКЦИЯ СТАТУСА, не накопление (§4, конституция III):
 * канал at-least-once — дубли возвращают ТОТ ЖЕ срез (без
 * ре-рендера), а состояние помнит завершённые конференции
 * (tombstone по chatId → conferenceId), поэтому ВНЕПОРЯДКОВЫЙ
 * `started` ПОСЛЕ `ended` той же конференции бейдж НЕ поднимает
 * (зеркало T057-кейса «поздний participant_joined» на клиенте:
 * жизненный цикл — одно статусное поле, переходы только вперёд).
 *
 * REST-fallback №45 (§4 «полнота»): на каждом (пере)подключении SSE
 * каждый поднятый бейдж перепроверяется GET /conferences/{id} —
 * `status='active'` держит бейдж (кадр мог быть потерян), `ended` —
 * снимает и ставит tombstone по id; транспорт-ошибка сохраняет состояние
 * (best-effort), проблемный ОТВЕТ (сервер авторитетно отрицает
 * конференцию для принципала) — снимает бейдж.
 */

const realtime = vi.hoisted(() => {
  const conferenceListeners = new Set<(event: ConferenceRealtimeEvent) => void>()
  const openListeners = new Set<() => void>()
  return {
    conferenceListeners,
    openListeners,
    stream: {
      onConferenceEvent(listener: (event: ConferenceRealtimeEvent) => void) {
        conferenceListeners.add(listener)
        return () => {
          conferenceListeners.delete(listener)
        }
      },
      onOpen(listener: () => void) {
        openListeners.add(listener)
        return () => {
          openListeners.delete(listener)
        }
      },
    },
  }
})

vi.mock('../../../chats/hooks/useRealtime', () => ({
  useRealtime: () => realtime.stream,
}))

const api = vi.hoisted(() => ({
  getConference: vi.fn(),
}))

vi.mock('../../api/conferences', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../api/conferences')>()),
  getConference: api.getConference,
}))

const CHAT_A = '7dc5dc5d-dc5d-4dc5-8dc5-dc5dc5dc5dc5'
const CHAT_B = '9a2c9a2c-9a2c-49a2-89a2-c9a2c9a2c9a2'
const CONFERENCE_1 = 'aaaaaaaa-0000-4000-8000-000000000001'
const CONFERENCE_2 = 'bbbbbbbb-0000-4000-8000-000000000002'
const STARTED_AT = '2026-10-17T12:00:00.000Z'
const ENDED_AT = '2026-10-17T12:30:00.000Z'

function startedEvent(
  chatId: string = CHAT_A,
  conferenceId: string = CONFERENCE_1,
  mode: 'video' | 'audio' = 'video',
): ConferenceRealtimeEvent {
  return { type: 'conference.started', conferenceId, chatId, startedAt: STARTED_AT, mode }
}

function endedEvent(
  chatId: string = CHAT_A,
  conferenceId: string = CONFERENCE_1,
  reason: 'last_left' | 'moderator' | 'max_duration' | 'room_lost' | 'chat_deleted' = 'moderator',
): ConferenceRealtimeEvent {
  return { type: 'conference.ended', conferenceId, chatId, endedAt: ENDED_AT, reason }
}

function view(overrides: Partial<ConferenceView> = {}): ConferenceView {
  return {
    id: CONFERENCE_1,
    chatId: CHAT_A,
    status: 'active',
    mode: 'video',
    createdBy: '11111111-1111-1111-1111-111111111111',
    createdAt: '2026-10-17T11:59:00.000Z',
    maxParticipants: 16,
    activeParticipants: 1,
    ...overrides,
  }
}

function problem(status: number, code: string): ApiProblem {
  return { title: `HTTP ${status}`, status, errors: { conference: [code] } }
}

describe('applyConferenceEvent — started (§1, US5-1)', () => {
  it('поднимает бейдж чата: byChat получает {conferenceId, mode, startedAt}', () => {
    const next = applyConferenceEvent(
      EMPTY_CONFERENCE_SLICE,
      startedEvent(CHAT_A, CONFERENCE_1, 'audio'),
    )

    expect(next.byChat.get(CHAT_A)).toEqual({
      conferenceId: CONFERENCE_1,
      mode: 'audio',
      startedAt: STARTED_AT,
    })
    expect(next.endedByChat.size).toBe(0)
  })

  it('дубль started того же conferenceId — ТОТ ЖЕ срез (без ре-рендера, §4)', () => {
    const first = applyConferenceEvent(EMPTY_CONFERENCE_SLICE, startedEvent())
    expect(applyConferenceEvent(first, startedEvent())).toBe(first)
  })

  it('started ДРУГОЙ конференции того же чата заменяет запись (одна активная на чат)', () => {
    const first = applyConferenceEvent(EMPTY_CONFERENCE_SLICE, startedEvent())
    const next = applyConferenceEvent(first, startedEvent(CHAT_A, CONFERENCE_2))

    expect(next.byChat.get(CHAT_A)?.conferenceId).toBe(CONFERENCE_2)
  })

  it('стартовые дефолты №44 разных чатов живут раздельно', () => {
    let slice = applyConferenceEvent(
      EMPTY_CONFERENCE_SLICE,
      startedEvent(CHAT_A, CONFERENCE_1, 'video'),
    )
    slice = applyConferenceEvent(slice, startedEvent(CHAT_B, CONFERENCE_2, 'audio'))

    expect(slice.byChat.get(CHAT_A)?.mode).toBe('video')
    expect(slice.byChat.get(CHAT_B)?.mode).toBe('audio')
  })
})

describe('applyConferenceEvent — ended (§2)', () => {
  it('снимает бейдж и ставит tombstone по id конференции', () => {
    const live = applyConferenceEvent(EMPTY_CONFERENCE_SLICE, startedEvent())
    const next = applyConferenceEvent(live, endedEvent())

    expect(next.byChat.has(CHAT_A)).toBe(false)
    expect(next.endedByChat.get(CHAT_A)?.conferenceId).toBe(CONFERENCE_1)
  })

  it('дубль ended — ТОТ ЖЕ срез (идемпотентность)', () => {
    const live = applyConferenceEvent(EMPTY_CONFERENCE_SLICE, startedEvent())
    const ended = applyConferenceEvent(live, endedEvent())
    expect(applyConferenceEvent(ended, endedEvent())).toBe(ended)
  })

  it('внепорядковый started ПОСЛЕ ended той же конференции бейдж НЕ поднимает (конституция III, §4)', () => {
    const live = applyConferenceEvent(EMPTY_CONFERENCE_SLICE, startedEvent())
    const ended = applyConferenceEvent(live, endedEvent())

    // внепорядковый повтор доставки старого started — tombstone держит
    expect(applyConferenceEvent(ended, startedEvent())).toBe(ended)
    expect(applyConferenceEvent(ended, startedEvent()).byChat.size).toBe(0)
  })

  it('ended ДРУГОЙ конференции не трогает чужой бейдж (tombstone — по id)', () => {
    const live = applyConferenceEvent(EMPTY_CONFERENCE_SLICE, startedEvent(CHAT_A, CONFERENCE_1))
    const next = applyConferenceEvent(live, endedEvent(CHAT_A, CONFERENCE_2))

    expect(next.byChat.get(CHAT_A)?.conferenceId).toBe(CONFERENCE_1)
    expect(next.endedByChat.get(CHAT_A)?.conferenceId).toBe(CONFERENCE_2)
  })

  it('ended для чата без бейджа всё равно ставит tombstone по id (потерянный started не воскресит)', () => {
    const next = applyConferenceEvent(EMPTY_CONFERENCE_SLICE, endedEvent())

    expect(next.byChat.size).toBe(0)
    expect(next.endedByChat.get(CHAT_A)?.conferenceId).toBe(CONFERENCE_1)
    expect(applyConferenceEvent(next, startedEvent()).byChat.size).toBe(0)
  })
})

describe('applyConferenceView — REST-fallback №45 как функция статуса (§4)', () => {
  it('status=active — бейдж стоит с полями ответа (кадры могли не дойти)', () => {
    const next = applyConferenceView(EMPTY_CONFERENCE_SLICE, view({ status: 'active' }))

    expect(next.byChat.get(CHAT_A)?.conferenceId).toBe(CONFERENCE_1)
  })

  it('status=active тем же id/режиму — ТОТ ЖЕ срез (идемпотентность)', () => {
    const live = applyConferenceEvent(EMPTY_CONFERENCE_SLICE, startedEvent())
    expect(applyConferenceView(live, view({ status: 'active', startedAt: STARTED_AT }))).toBe(live)
  })

  it('status=created — бейдж снят, tombstone НЕ ставится (конференция ещё может начаться)', () => {
    const live = applyConferenceEvent(EMPTY_CONFERENCE_SLICE, startedEvent())
    const next = applyConferenceView(live, view({ status: 'created', startedAt: undefined }))

    expect(next.byChat.has(CHAT_A)).toBe(false)
    expect(next.endedByChat.size).toBe(0)
    // поздний фактический старт поднимет бейдж снова
    expect(applyConferenceEvent(next, startedEvent()).byChat.has(CHAT_A)).toBe(true)
  })

  it('status=ended — бейдж снят + tombstone id (поздний потерянный started не поднимет)', () => {
    const live = applyConferenceEvent(EMPTY_CONFERENCE_SLICE, startedEvent())
    const next = applyConferenceView(live, view({ status: 'ended' }))

    expect(next.byChat.has(CHAT_A)).toBe(false)
    expect(next.endedByChat.get(CHAT_A)?.conferenceId).toBe(CONFERENCE_1)
    expect(applyConferenceEvent(next, startedEvent()).byChat.size).toBe(0)
  })

  it('ended-ответ чужого id не трогает текущий бейдж, но ставит tombstone завершённого id', () => {
    const live = applyConferenceEvent(EMPTY_CONFERENCE_SLICE, startedEvent(CHAT_A, CONFERENCE_1))
    const next = applyConferenceView(live, view({ id: CONFERENCE_2, status: 'ended' }))

    expect(next.byChat.get(CHAT_A)?.conferenceId).toBe(CONFERENCE_1)
    expect(next.endedByChat.get(CHAT_A)?.conferenceId).toBe(CONFERENCE_2)
  })
})

describe('tombstone несёт span длительности завершённой конференции (009 T071)', () => {
  it('ended при поднятом бейдже — startedAt из бейджа (№17) + endedAt кадра №18', () => {
    const live = applyConferenceEvent(EMPTY_CONFERENCE_SLICE, startedEvent())
    const next = applyConferenceEvent(live, endedEvent())

    expect(next.endedByChat.get(CHAT_A)).toEqual({
      conferenceId: CONFERENCE_1,
      startedAt: STARTED_AT,
      endedAt: ENDED_AT,
    })
  })

  it('ended без бейджа (started-кадр не доставлялся) — tombstone без startedAt: длительности нет', () => {
    const next = applyConferenceEvent(EMPTY_CONFERENCE_SLICE, endedEvent())

    expect(next.endedByChat.get(CHAT_A)).toEqual({
      conferenceId: CONFERENCE_1,
      endedAt: ENDED_AT,
    })
  })

  it('дубль ended — ТОТ ЖЕ срез (span не пересобирается, идемпотентность)', () => {
    const live = applyConferenceEvent(EMPTY_CONFERENCE_SLICE, startedEvent())
    const ended = applyConferenceEvent(live, endedEvent())
    expect(applyConferenceEvent(ended, endedEvent())).toBe(ended)
  })

  it('REST-fallback №45 ended — endedAt из ответа, startedAt из бейджа (№17 приоритетнее полей ответа)', () => {
    const live = applyConferenceEvent(EMPTY_CONFERENCE_SLICE, startedEvent())
    const next = applyConferenceView(
      live,
      view({
        status: 'ended',
        startedAt: '2026-10-17T12:01:00.000Z',
        endedAt: ENDED_AT,
      }),
    )

    expect(next.endedByChat.get(CHAT_A)).toEqual({
      conferenceId: CONFERENCE_1,
      startedAt: STARTED_AT,
      endedAt: ENDED_AT,
    })
  })

  it('REST-fallback №45 ended без бейджа — span из полей ответа (оба поля ended-вида)', () => {
    const next = applyConferenceView(
      EMPTY_CONFERENCE_SLICE,
      view({ status: 'ended', startedAt: STARTED_AT, endedAt: ENDED_AT }),
    )

    expect(next.endedByChat.get(CHAT_A)).toEqual({
      conferenceId: CONFERENCE_1,
      startedAt: STARTED_AT,
      endedAt: ENDED_AT,
    })
  })
})

function emitConferenceEvent(event: ConferenceRealtimeEvent): void {
  act(() => {
    for (const listener of realtime.conferenceListeners) {
      listener(event)
    }
  })
}

function emitOpen(): void {
  act(() => {
    for (const listener of realtime.openListeners) {
      listener()
    }
  })
}

const mounted: Array<{ unmount(): void }> = []

function mountConferenceRealtime() {
  const rendered = renderHook(() => useConferenceRealtime())
  mounted.push(rendered)
  return rendered
}

describe('useConferenceRealtime — живой срез по кадрам №18 (T059)', () => {
  beforeEach(() => {
    api.getConference.mockReset()
  })

  afterEach(() => {
    for (const rendered of mounted.splice(0)) {
      rendered.unmount()
    }
    cleanup()
    vi.clearAllMocks()
  })

  it('started поднимает бейдж, ended снимает — живой цикл индикатора', () => {
    const { result } = mountConferenceRealtime()

    emitConferenceEvent(startedEvent(CHAT_A, CONFERENCE_1, 'audio'))
    expect(result.current.byChat.get(CHAT_A)).toEqual({
      conferenceId: CONFERENCE_1,
      mode: 'audio',
      startedAt: STARTED_AT,
    })

    emitConferenceEvent(endedEvent())
    expect(result.current.byChat.size).toBe(0)
    expect(result.current.endedByChat.get(CHAT_A)?.conferenceId).toBe(CONFERENCE_1)
  })

  it('внепорядковый started после ended не поднимает бейдж живого хука (функция статуса)', () => {
    const { result } = mountConferenceRealtime()

    emitConferenceEvent(startedEvent())
    emitConferenceEvent(endedEvent())
    emitConferenceEvent(startedEvent())

    expect(result.current.byChat.size).toBe(0)
  })
})

describe('useConferenceRealtime — REST-fallback №45 на (пере)подключении SSE (§4)', () => {
  beforeEach(() => {
    api.getConference.mockReset()
  })

  afterEach(() => {
    for (const rendered of mounted.splice(0)) {
      rendered.unmount()
    }
    cleanup()
    vi.clearAllMocks()
  })

  it('переподключение перепроверяет поднятый бейдж: ended-ответ снимает и держит tombstone', async () => {
    api.getConference.mockResolvedValue(view({ status: 'ended' }))
    const { result } = mountConferenceRealtime()

    emitConferenceEvent(startedEvent())
    emitOpen()

    await waitFor(() => {
      expect(result.current.byChat.size).toBe(0)
    })
    expect(api.getConference).toHaveBeenCalledWith(CONFERENCE_1)

    // поздний дубль started той же конференции — tombstone держит
    emitConferenceEvent(startedEvent())
    expect(result.current.byChat.size).toBe(0)
  })

  it('active-ответ держит бейдж (кадр был, сервер подтверждает)', async () => {
    api.getConference.mockResolvedValue(view({ status: 'active' }))
    const { result } = mountConferenceRealtime()

    emitConferenceEvent(startedEvent())
    emitOpen()

    await waitFor(() => {
      expect(api.getConference).toHaveBeenCalledTimes(1)
    })
    await act(async () => {})
    expect(result.current.byChat.get(CHAT_A)?.conferenceId).toBe(CONFERENCE_1)
  })

  it('транспорт-ошибка №45 сохраняет бейдж (best-effort: следующий (re)open повторит)', async () => {
    api.getConference.mockRejectedValue(new Error('network down'))
    const { result } = mountConferenceRealtime()

    emitConferenceEvent(startedEvent())
    emitOpen()

    await waitFor(() => {
      expect(api.getConference).toHaveBeenCalledTimes(1)
    })
    await act(async () => {})
    expect(result.current.byChat.get(CHAT_A)?.conferenceId).toBe(CONFERENCE_1)
  })

  it('проблемный ОТВЕТ №45 (404 не-члену) снимает бейдж — сервер отрицает конференцию', async () => {
    api.getConference.mockRejectedValue(problem(404, 'conference_not_found'))
    const { result } = mountConferenceRealtime()

    emitConferenceEvent(startedEvent())
    emitOpen()

    await waitFor(() => {
      expect(result.current.byChat.size).toBe(0)
    })
  })

  it('без поднятых бейджей (пере)подключение ничего не дергает', async () => {
    const { result } = mountConferenceRealtime()

    emitOpen()
    await act(async () => {})

    expect(api.getConference).not.toHaveBeenCalled()
    expect(result.current.byChat.size).toBe(0)
  })
})
