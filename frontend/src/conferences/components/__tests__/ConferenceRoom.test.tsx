import { cleanup, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { JoinTicket } from '../../api/conferences'

/**
 * 009, US1, T029 — ConferenceRoom (T027, FR-004/FR-006): подключение по
 * JoinTicket (wsUrl+token), стартовая публикация по mode/initialMedia,
 * отказ устройств не рушит вход («только слушаю»), серверные разрывы —
 * терминальные без авто-возврата, полный разрыв после успешного
 * подключения — единственная попытка refreshTicket (№47) и
 * переподключение свежим тикетом (FR-014); размонтирование рвёт
 * подключение (best-effort). livekit-client и примитивы
 * @livekit/components-react заменены фейками: предмет теста — связка
 * нашей комнаты с SDK-контрактом (connect/disconnect/события/сеттеры).
 */

const lk = vi.hoisted(() => {
  const ConnectionState = { Disconnected: 0, Connected: 2 } as const
  const DisconnectReason = {
    UNKNOWN: 0,
    CLIENT_INITIATED: 1,
    PARTICIPANT_REMOVED: 11,
    ROOM_DELETED: 12,
    ROOM_CLOSED: 13,
    DUPLICATE_IDENTITY: 14,
    USER_REJECTED: 15,
  } as const
  const RoomEvent = {
    ParticipantConnected: 'participantConnected',
    Reconnecting: 'reconnecting',
    Reconnected: 'reconnected',
    Disconnected: 'disconnected',
  } as const
  const Track = {
    Source: { Camera: 'camera', Microphone: 'microphone', ScreenShare: 'screen_share' },
  }

  /** Fake LiveKit Room: контракт методов/событий, используемых ConferenceRoom. */
  class FakeRoom {
    static readonly instances: FakeRoom[] = []
    /** Поведение connect по умолчанию для НОВЫХ вызовов (задаётся тестом до монтирования). */
    static defaultConnectImpl: ((wsUrl: string, token: string) => Promise<void>) | null = null
    readonly constructorOptions: unknown
    state: number = ConnectionState.Disconnected
    readonly connectCalls: { wsUrl: string; token: string }[] = []
    readonly micCalls: boolean[] = []
    readonly cameraCalls: boolean[] = []
    disconnectCalls = 0
    /** Управляемое тестом поведение connect конкретной комнаты (приоритет над default). */
    connectImpl: ((wsUrl: string, token: string) => Promise<void>) | null = null
    micImpl: ((enabled: boolean) => Promise<void>) | null = null
    cameraImpl: ((enabled: boolean) => Promise<void>) | null = null
    private readonly listeners = new Map<string, Set<(reason?: number) => void>>()

    constructor(options: unknown) {
      this.constructorOptions = options
      FakeRoom.instances.push(this)
    }

    on(event: string, listener: (reason?: number) => void): this {
      let set = this.listeners.get(event)
      if (set === undefined) {
        set = new Set()
        this.listeners.set(event, set)
      }
      set.add(listener)
      return this
    }

    emit(event: string, reason?: number): void {
      this.listeners.get(event)?.forEach((listener) => {
        listener(reason)
      })
    }

    removeAllListeners(): void {
      this.listeners.clear()
    }

    async connect(wsUrl: string, token: string): Promise<void> {
      this.connectCalls.push({ wsUrl, token })
      const impl = this.connectImpl ?? FakeRoom.defaultConnectImpl
      if (impl !== null) {
        await impl(wsUrl, token)
      }
      this.state = ConnectionState.Connected
    }

    disconnect(): Promise<void> {
      this.disconnectCalls++
      this.state = ConnectionState.Disconnected
      return Promise.resolve()
    }

    get localParticipant(): {
      setMicrophoneEnabled: (enabled: boolean) => Promise<void>
      setCameraEnabled: (enabled: boolean) => Promise<void>
    } {
      return {
        setMicrophoneEnabled: async (enabled) => {
          this.micCalls.push(enabled)
          if (this.micImpl !== null) {
            await this.micImpl(enabled)
          }
        },
        setCameraEnabled: async (enabled) => {
          this.cameraCalls.push(enabled)
          if (this.cameraImpl !== null) {
            await this.cameraImpl(enabled)
          }
        },
      }
    }
  }

  return { ConnectionState, DisconnectReason, RoomEvent, Track, FakeRoom }
})

vi.mock('livekit-client', () => ({
  Room: lk.FakeRoom,
  RoomEvent: lk.RoomEvent,
  ConnectionState: lk.ConnectionState,
  DisconnectReason: lk.DisconnectReason,
  Track: lk.Track,
}))

/** Состояние треков useTracks для ConferenceMedia (управляется тестом). */
const lkc = vi.hoisted(() => ({
  trackRefs: [] as ReadonlyArray<{
    participant: { identity: string; name?: string }
    source: string
  }>,
}))

vi.mock('@livekit/components-react', () => ({
  RoomContext: {
    Provider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  },
  useTracks: (selectors: unknown) => {
    const list = Array.isArray(selectors) ? selectors : [selectors]
    const wanted = new Set(
      list.map((selector) =>
        typeof selector === 'string' ? selector : (selector as { source: string }).source,
      ),
    )
    return lkc.trackRefs.filter((ref) => wanted.has(ref.source))
  },
  RoomAudioRenderer: () => null,
  ParticipantTile: (props: { readonly trackRef?: { participant: { identity: string } } }) => (
    <div data-testid="participant-tile">{props.trackRef?.participant.identity}</div>
  ),
  VideoTrack: () => <video />,
}))

import ConferenceRoom from '../ConferenceRoom'

function ticket(token: string, overrides: Partial<JoinTicket> = {}): JoinTicket {
  return {
    token,
    wsUrl: 'wss://media.example.com',
    identity: 'u:11111111-1111-1111-1111-111111111111:conn-1',
    name: 'alice',
    expiresAt: '2026-10-17T12:15:00.000Z',
    ...overrides,
  }
}

function lastRoom(): InstanceType<typeof lk.FakeRoom> {
  const room = lk.FakeRoom.instances[lk.FakeRoom.instances.length - 1]
  if (room === undefined) {
    throw new Error('FakeRoom не создан — ConferenceRoom не монтировался')
  }
  return room
}

describe('ConferenceRoom (009 US1 T027/T029)', () => {
  beforeEach(() => {
    lk.FakeRoom.instances.length = 0
    lkc.trackRefs = []
  })

  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it('подключается по JoinTicket (wsUrl+token) и публикует микрофон+камеру для mode=video', async () => {
    const refreshTicket = vi.fn()
    render(
      <ConferenceRoom
        conferenceId="c-1"
        ticket={ticket('jwt-1')}
        mode="video"
        refreshTicket={refreshTicket}
      />,
    )

    await waitFor(() => {
      expect(lastRoom().connectCalls).toEqual([
        { wsUrl: 'wss://media.example.com', token: 'jwt-1' },
      ])
    })
    await waitFor(() => {
      expect(lastRoom().micCalls).toEqual([true])
    })
    await waitFor(() => {
      expect(lastRoom().cameraCalls).toEqual([true])
    })
    // connected — статус-строка подключения исчезает
    await waitFor(() => {
      expect(screen.queryByRole('status')).toBeNull()
    })
  })

  it('mode=audio: старт — только микрофон (камера не публикуется, Q3=A soft)', async () => {
    render(
      <ConferenceRoom
        conferenceId="c-1"
        ticket={ticket('jwt-1')}
        mode="audio"
        refreshTicket={vi.fn()}
      />,
    )

    await waitFor(() => {
      expect(lastRoom().micCalls).toEqual([true])
    })
    await waitFor(() => {
      expect(lastRoom().cameraCalls).toEqual([false])
    })
  })

  it('initialMedia {оба выкл} — вход «только слушаю», публикаций нет (FR-006)', async () => {
    render(
      <ConferenceRoom
        conferenceId="c-1"
        ticket={ticket('jwt-1')}
        mode="video"
        initialMedia={{ microphone: false, camera: false }}
        refreshTicket={vi.fn()}
      />,
    )

    await waitFor(() => {
      expect(lastRoom().connectCalls).toHaveLength(1)
    })
    await waitFor(() => {
      expect(lastRoom().micCalls).toEqual([false])
    })
    await waitFor(() => {
      expect(lastRoom().cameraCalls).toEqual([false])
    })
  })

  it('отказ браузера в доступе к устройствам не рушит вход — участник остаётся слушателем', async () => {
    render(
      <ConferenceRoom
        conferenceId="c-1"
        ticket={ticket('jwt-1')}
        mode="video"
        refreshTicket={vi.fn()}
      />,
    )
    const room = lastRoom()
    room.micImpl = () => Promise.reject(new Error('NotAllowedError'))
    room.cameraImpl = () => Promise.reject(new Error('NotFoundError'))

    await waitFor(() => {
      expect(room.connectCalls).toHaveLength(1)
    })
    await waitFor(() => {
      expect(room.micCalls).toEqual([true])
    })
    await waitFor(() => {
      expect(room.cameraCalls).toEqual([true])
    })
    // вход сохранён: подключение не разорвано, ошибки на экране нет
    await waitFor(() => {
      expect(screen.queryByRole('status')).toBeNull()
    })
    expect(room.disconnectCalls).toBe(0)
  })

  it('серверный разрыв (PARTICIPANT_REMOVED) — терминальный «Вы отключены», без refreshTicket', async () => {
    const refreshTicket = vi.fn()
    render(
      <ConferenceRoom
        conferenceId="c-1"
        ticket={ticket('jwt-1')}
        mode="video"
        refreshTicket={refreshTicket}
      />,
    )
    const room = lastRoom()
    await waitFor(() => {
      expect(room.connectCalls).toHaveLength(1)
    })
    await waitFor(() => {
      expect(room.micCalls).toHaveLength(1)
    })

    room.emit(lk.RoomEvent.Disconnected, lk.DisconnectReason.PARTICIPANT_REMOVED)

    const status = await screen.findByRole('status')
    expect(status).toHaveTextContent('Вы отключены от конференции')
    expect(refreshTicket).not.toHaveBeenCalled()
    expect(room.connectCalls).toHaveLength(1)
  })

  it('полный разрыв после подключения: refreshTicket (№47) → свежая комната с новым тикетом (FR-014)', async () => {
    const ticketTwo = ticket('jwt-2', {
      identity: 'u:11111111-1111-1111-1111-111111111111:conn-2',
    })
    const refreshTicket = vi.fn().mockResolvedValue(ticketTwo)
    const view = render(
      <ConferenceRoom
        conferenceId="c-1"
        ticket={ticket('jwt-1')}
        mode="video"
        refreshTicket={refreshTicket}
      />,
    )
    const first = lastRoom()
    await waitFor(() => {
      expect(first.connectCalls).toHaveLength(1)
    })

    first.state = lk.ConnectionState.Disconnected
    first.emit(lk.RoomEvent.Disconnected)

    await waitFor(() => {
      expect(refreshTicket).toHaveBeenCalledTimes(1)
    })
    await screen.findByText('Соединение потеряно — переподключение…')

    // повторный разрыв той же цепочки: попытка обновления уже потрачена —
    // второго refreshTicket нет, фаза — контролируемое «не удалось»
    first.emit(lk.RoomEvent.Disconnected)
    expect(refreshTicket).toHaveBeenCalledTimes(1)
    expect(await screen.findByRole('status')).toHaveTextContent(
      'Не удалось подключиться к медиа-серверу',
    )

    // родитель перевыдал тикет (смена ticket.token) — СВЕЖАЯ комната
    // подключается новым токеном, прежняя корректно разорвана
    view.rerender(
      <ConferenceRoom
        conferenceId="c-1"
        ticket={ticketTwo}
        mode="video"
        refreshTicket={refreshTicket}
      />,
    )

    const second = lastRoom()
    expect(second).not.toBe(first)
    await waitFor(() => {
      expect(second.connectCalls).toEqual([{ wsUrl: 'wss://media.example.com', token: 'jwt-2' }])
    })
    expect(first.disconnectCalls).toBeGreaterThanOrEqual(1)
    await waitFor(() => {
      expect(screen.queryByRole('status')).toBeNull()
    })
  })

  it('сбой подключения до успеха — «Не удалось подключиться к медиа-серверу», без refreshTicket', async () => {
    lk.FakeRoom.defaultConnectImpl = () => Promise.reject(new Error('signal failed'))
    try {
      const refreshTicket = vi.fn()
      render(
        <ConferenceRoom
          conferenceId="c-1"
          ticket={ticket('jwt-1')}
          mode="video"
          refreshTicket={refreshTicket}
        />,
      )
      const room = lastRoom()

      const status = await screen.findByRole('status')
      expect(status).toHaveTextContent('Не удалось подключиться к медиа-серверу')
      expect(refreshTicket).not.toHaveBeenCalled()
      expect(room.connectCalls).toHaveLength(1)
    } finally {
      lk.FakeRoom.defaultConnectImpl = null
    }
  })

  it('смена тикета при живой комнате — полное переподключение: свежая комната, прежняя разорвана', async () => {
    const view = render(
      <ConferenceRoom
        conferenceId="c-1"
        ticket={ticket('jwt-1')}
        mode="video"
        refreshTicket={vi.fn()}
      />,
    )
    const first = lastRoom()
    await waitFor(() => {
      expect(first.connectCalls).toHaveLength(1)
    })

    view.rerender(
      <ConferenceRoom
        conferenceId="c-1"
        ticket={ticket('jwt-2')}
        mode="video"
        refreshTicket={vi.fn()}
      />,
    )

    const second = lastRoom()
    expect(second).not.toBe(first)
    await waitFor(() => {
      expect(second.connectCalls).toEqual([{ wsUrl: 'wss://media.example.com', token: 'jwt-2' }])
    })
    expect(first.disconnectCalls).toBeGreaterThanOrEqual(1)
    expect(first.connectCalls).toHaveLength(1)
  })

  it('размонтирование рвёт подключение (best-effort disconnect)', async () => {
    const view = render(
      <ConferenceRoom
        conferenceId="c-1"
        ticket={ticket('jwt-1')}
        mode="video"
        refreshTicket={vi.fn()}
      />,
    )
    const room = lastRoom()
    await waitFor(() => {
      expect(room.connectCalls).toHaveLength(1)
    })

    view.unmount()

    expect(room.disconnectCalls).toBeGreaterThanOrEqual(1)
  })

  it('медиа-слой: сетка участников и широкие тайлы демонстраций экрана', async () => {
    lkc.trackRefs = [
      { participant: { identity: 'u:1:a', name: 'alice' }, source: 'camera' },
      { participant: { identity: 'u:2:b', name: 'bob' }, source: 'screen_share' },
    ]
    render(
      <ConferenceRoom
        conferenceId="c-1"
        ticket={ticket('jwt-1')}
        mode="video"
        refreshTicket={vi.fn()}
      />,
    )

    const tiles = await screen.findByRole('region', { name: 'Участники конференции' })
    expect(tiles).toHaveTextContent('u:1:a')
    const screens = screen.getByRole('region', { name: 'Демонстрации экрана' })
    // подпись широкого тайла — отображаемое имя участника (participantLabel)
    expect(screens).toHaveTextContent('Экран — bob')
    expect(screens.querySelector('video')).not.toBeNull()
  })
})
