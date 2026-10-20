import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { JoinTicket } from '../../api/conferences'

/**
 * 009, US1, T029 (+ T032, US2, Q6=A) — ConferenceRoom (T027,
 * FR-004/FR-006): подключение по JoinTicket (wsUrl+token), стартовая
 * публикация по mode/initialMedia, отказ устройств не рушит вход
 * («только слушаю») — понятное предупреждение и снятие при успешном
 * повторном включении уже в звонке (LocalTrackPublished, тумблеры T028);
 * серверные разрывы — терминальные без авто-возврата, полный разрыв
 * после успешного подключения — цепочка реконнекта в окне
 * reconnect-window со свежими тикетами №47 (T060, FR-014); livekit-client и примитивы
 * @livekit/components-react заменены фейками: предмет теста — связка
 * нашей комнаты с SDK-контрактом (connect/disconnect/события/сеттеры).
 *
 * 009, US5, T061 — полные сценарии окна реконнекта T060 (Q15=B):
 * индикация потери связи «переподключение…» и КОНТРОЛИРУЕМОЕ
 * завершение по истечении reconnect-window («lost», ретраи минта
 * прекращаются); тост предупреждения о лимите длительности по
 * data-пакету topic `conference.warning` (T056 → T060, FR-015,
 * Q16=B) с ручным закрытием и нейтральным игнором чужих тем/битого
 * payload.
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
    DataReceived: 'dataReceived',
    LocalTrackPublished: 'localTrackPublished',
    ActiveDeviceChanged: 'activeDeviceChanged',
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
    /** switchActiveDevice (useDevices — стартовый выбор pre-join, T034). */
    readonly switchCalls: { kind: string; deviceId: string }[] = []
    disconnectCalls = 0
    /** Управляемое тестом поведение connect конкретной комнаты (приоритет над default). */
    connectImpl: ((wsUrl: string, token: string) => Promise<void>) | null = null
    micImpl: ((enabled: boolean) => Promise<void>) | null = null
    cameraImpl: ((enabled: boolean) => Promise<void>) | null = null
    private readonly listeners = new Map<string, Set<(...args: unknown[]) => void>>()

    constructor(options: unknown) {
      this.constructorOptions = options
      FakeRoom.instances.push(this)
    }

    on(event: string, listener: (...args: unknown[]) => void): this {
      let set = this.listeners.get(event)
      if (set === undefined) {
        set = new Set()
        this.listeners.set(event, set)
      }
      set.add(listener)
      return this
    }

    off(event: string, listener: (...args: unknown[]) => void): this {
      this.listeners.get(event)?.delete(listener)
      return this
    }

    switchActiveDevice(kind: string, deviceId: string): Promise<boolean> {
      this.switchCalls.push({ kind, deviceId })
      return Promise.resolve(true)
    }

    /** Вариадический emit: DataReceived несёт (payload, participant, kind, topic). */
    emit(event: string, ...args: unknown[]): void {
      this.listeners.get(event)?.forEach((listener) => {
        listener(...args)
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

/** Состояние треков/спикеров useTracks/useSpeakingParticipants (управляется тестом). */
const lkc = vi.hoisted(() => ({
  trackRefs: [] as ReadonlyArray<{
    participant: { identity: string; name?: string }
    source: string
    publication?: { trackSid: string }
  }>,
  speakers: [] as ReadonlyArray<{ identity: string }>,
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
  useSpeakingParticipants: () => lkc.speakers,
  // зеркало реального гварда ядра: placeholder-рефы не несут publication
  isTrackReference: (ref: { publication?: unknown }) => ref.publication !== undefined,
  RoomAudioRenderer: () => <div data-testid="lk-audio-renderer" />,
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

  it('стартовый выбор устройств pre-join применяется при появлении комнаты (T034, useDevices initial)', async () => {
    render(
      <ConferenceRoom
        conferenceId="c-1"
        ticket={ticket('jwt-1')}
        mode="video"
        initialMedia={{ microphone: true, camera: false }}
        initialDevices={{
          audioInput: 'mic-2',
          videoInput: 'cam-2',
          audioOutput: 'spk-2',
        }}
        refreshTicket={vi.fn()}
      />,
    )

    // комната появилась — выбор применён ДО первой публикации (null-слоты
    // не адресуются: «По умолчанию» остаётся браузерному дефолту)
    await waitFor(() => {
      expect(lastRoom().switchCalls).toEqual([
        { kind: 'audioinput', deviceId: 'mic-2' },
        { kind: 'videoinput', deviceId: 'cam-2' },
        { kind: 'audiooutput', deviceId: 'spk-2' },
      ])
    })
    await waitFor(() => {
      expect(lastRoom().micCalls).toEqual([true])
    })
    await waitFor(() => {
      expect(lastRoom().cameraCalls).toEqual([false])
    })
  })

  it('отказ браузера в доступе к устройствам не рушит вход — слушатель + понятное предупреждение (Q6=A, T032)', async () => {
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
    // вход сохранён: подключение не разорвано; отказ — предупреждение
    // «только слушаю» с подсказкой повторного запроса в звонке
    expect(
      await screen.findByText('Нет доступа к камере и микрофону — можно включить их в звонке'),
    ).toBeVisible()
    expect(room.disconnectCalls).toBe(0)
  })

  it('отказ только микрофона (mode=audio) — предупреждение про микрофон, вход сохранён (T032)', async () => {
    render(
      <ConferenceRoom
        conferenceId="c-1"
        ticket={ticket('jwt-1')}
        mode="audio"
        refreshTicket={vi.fn()}
      />,
    )
    const room = lastRoom()
    room.micImpl = () => Promise.reject(new Error('NotAllowedError'))

    await waitFor(() => {
      expect(room.micCalls).toEqual([true])
    })
    expect(
      await screen.findByText('Нет доступа к микрофону — можно включить его в звонке'),
    ).toBeVisible()
    // камера не запрашивалась (mode=audio) — её части в предупреждении нет
    expect(screen.queryByText(/Нет доступа к камере/)).toBeNull()
    expect(room.disconnectCalls).toBe(0)
  })

  it('намеренно выключенный старт не считается отказом — предупреждения нет (FR-006)', async () => {
    render(
      <ConferenceRoom
        conferenceId="c-1"
        ticket={ticket('jwt-1')}
        mode="video"
        initialMedia={{ microphone: false, camera: false }}
        refreshTicket={vi.fn()}
      />,
    )
    const room = lastRoom()

    await waitFor(() => {
      expect(room.micCalls).toEqual([false])
    })
    await waitFor(() => {
      expect(room.cameraCalls).toEqual([false])
    })
    await waitFor(() => {
      expect(screen.queryByText(/Нет доступа/)).toBeNull()
    })
  })

  it('повторный запрос уже в звонке: успешная публикация (LocalTrackPublished) снимает предупреждение слота (Q6=A, T032)', async () => {
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
    room.cameraImpl = () => Promise.reject(new Error('NotAllowedError'))

    await waitFor(() => {
      expect(room.cameraCalls).toEqual([true])
    })
    // оба слота недоступны — объединённое предупреждение
    expect(
      await screen.findByText('Нет доступа к камере и микрофону — можно включить их в звонке'),
    ).toBeVisible()

    // пользователь повторно запросил микрофон тумблером (T028) — успех:
    // LocalTrackPublished микрофона снимает только свою часть
    room.emit(lk.RoomEvent.LocalTrackPublished, { source: lk.Track.Source.Microphone })
    expect(
      await screen.findByText('Нет доступа к камере — можно включить её в звонке'),
    ).toBeVisible()

    // успешное включение камеры убирает предупреждение полностью
    room.emit(lk.RoomEvent.LocalTrackPublished, { source: lk.Track.Source.Camera })
    await waitFor(() => {
      expect(screen.queryByText(/Нет доступа/)).toBeNull()
    })

    // публикация экрана предупреждений не порождает и не трогает
    room.emit(lk.RoomEvent.LocalTrackPublished, { source: lk.Track.Source.ScreenShare })
    expect(screen.queryByText(/Нет доступа/)).toBeNull()
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

    // повторный разрыв той же мёртвой комнаты — дубликат цепочки
    // (окно реконнекта T060): тикет уже получен, второго минта нет,
    // фаза остаётся «переподключение…» до смены тикета родителем
    first.emit(lk.RoomEvent.Disconnected)
    expect(refreshTicket).toHaveBeenCalledTimes(1)
    expect(screen.getByRole('status')).toHaveTextContent('Соединение потеряно — переподключение…')

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
      // камера не опубликована — тайл-заглушка с аватаром и именем (T073)
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
    // подпись тайла — отображаемое имя участника (participantLabel)
    expect(tiles).toHaveTextContent('alice')
    expect(tiles.querySelector('.avatar')).not.toBeNull()
    const screens = screen.getByRole('region', { name: 'Демонстрации экрана' })
    // подпись широкого тайла — отображаемое имя участника (participantLabel)
    expect(screens).toHaveTextContent('Экран — bob')
    expect(screens.querySelector('video')).not.toBeNull()
    // тайлы демонстраций — без аватара (T073)
    expect(screens.querySelector('.avatar')).toBeNull()
  })
})

describe('ConferenceRoom — аватары тайлов (009 US1 T073, «как в контактах»)', () => {
  const ALICE = '11111111-1111-4111-8111-111111111111'

  /** Тайл по подписи-имени участника (figcaption). */
  function tileByLabel(name: string): HTMLElement {
    const label = screen.getByText(name, { exact: false })
    const tile = label.closest('figure')
    if (tile === null) {
      throw new Error(`тайл участника «${name}» не найден`)
    }
    return tile
  }

  function renderRoom(): ReturnType<typeof render> {
    return render(
      <ConferenceRoom
        conferenceId="c-1"
        ticket={ticket('jwt-1')}
        mode="video"
        refreshTicket={vi.fn()}
      />,
    )
  }

  beforeEach(() => {
    lk.FakeRoom.instances.length = 0
    lkc.trackRefs = []
    lkc.speakers = []
  })

  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it('тайл без камеры — аватар + имя; с опубликованной камерой — видео без аватара', async () => {
    lkc.trackRefs = [
      // bob: камера выключена — placeholder-реф без publication
      { participant: { identity: 'u:2:b', name: 'bob' }, source: 'camera' },
      // alice: камера опубликована — реальный track-реф
      {
        participant: { identity: `u:${ALICE}:c1`, name: 'alice' },
        source: 'camera',
        publication: { trackSid: 'cam-1' },
      },
    ]
    renderRoom()

    const bobTile = await waitFor(() => {
      const tile = tileByLabel('bob')
      expect(tile).toHaveClass('conference-tile-idle')
      return tile
    })
    expect(bobTile.querySelector('.avatar')).not.toBeNull()
    expect(bobTile.querySelector('video')).toBeNull()

    const aliceTile = tileByLabel('alice')
    expect(aliceTile.classList.contains('conference-tile-idle')).toBe(false)
    expect(aliceTile.querySelector('video')).not.toBeNull()
    expect(aliceTile.querySelector('.avatar')).toBeNull()
  })

  it('live-переход камеры: публикация убирает аватар с тайла, выключение возвращает', async () => {
    lkc.trackRefs = [{ participant: { identity: 'u:2:b', name: 'bob' }, source: 'camera' }]
    const view = renderRoom()

    await waitFor(() => {
      expect(tileByLabel('bob').querySelector('.avatar')).not.toBeNull()
    })

    // камера включилась — track-реф с publication
    lkc.trackRefs = [
      {
        participant: { identity: 'u:2:b', name: 'bob' },
        source: 'camera',
        publication: { trackSid: 'cam-1' },
      },
    ]
    view.rerender(
      <ConferenceRoom
        conferenceId="c-1"
        ticket={ticket('jwt-1')}
        mode="video"
        refreshTicket={vi.fn()}
      />,
    )
    await waitFor(() => {
      expect(tileByLabel('bob').querySelector('.avatar')).toBeNull()
    })
    expect(tileByLabel('bob').querySelector('video')).not.toBeNull()

    // камера выключилась — аватар вернулся
    lkc.trackRefs = [{ participant: { identity: 'u:2:b', name: 'bob' }, source: 'camera' }]
    view.rerender(
      <ConferenceRoom
        conferenceId="c-1"
        ticket={ticket('jwt-1')}
        mode="video"
        refreshTicket={vi.fn()}
      />,
    )
    await waitFor(() => {
      expect(tileByLabel('bob').querySelector('.avatar')).not.toBeNull()
    })
  })

  it('индикатор речи на тайле без камеры — текстом «говорит» (008: не только цветом)', async () => {
    lkc.trackRefs = [{ participant: { identity: 'u:2:b', name: 'bob' }, source: 'camera' }]
    lkc.speakers = [{ identity: 'u:2:b' }]
    renderRoom()

    const bobTile = await waitFor(() => {
      const tile = tileByLabel('bob')
      expect(tile).toHaveTextContent('говорит')
      return tile
    })
    expect(bobTile.querySelector('.avatar')).not.toBeNull()
  })

  it('без аватара в профиле участника — инициалы из name (фолбэк догрузки), тире-подпись не меняется', async () => {
    // гость без провайдера профилей: инициалы из введённого имени №55
    lkc.trackRefs = [
      {
        participant: { identity: 'g:22222222-2222-4222-8222-222222222222', name: 'Виктор' },
        source: 'camera',
      },
    ]
    renderRoom()

    const tile = await waitFor(() => tileByLabel('Виктор'))
    const avatar = tile.querySelector<HTMLElement>('.avatar')
    expect(avatar).not.toBeNull()
    expect(avatar?.querySelector('span')?.textContent).toBe('ВИ')
  })
})

describe('ConferenceRoom — окно реконнекта T060 (009 US5 T061, Q15=B)', () => {
  beforeEach(() => {
    lk.FakeRoom.instances.length = 0
    lkc.trackRefs = []
  })

  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it('потеря связи после подключения: индикация «переподключение…» и цепочка ретраев минта до конца окна', async () => {
    // null — минт не даёт свежего тикета (сбой/устаревание): ретраи
    // продолжаются, пока окно живо
    const refreshTicket = vi.fn().mockResolvedValue(null)
    render(
      <ConferenceRoom
        conferenceId="c-1"
        ticket={ticket('jwt-1')}
        mode="video"
        refreshTicket={refreshTicket}
        reconnectWindowMs={150}
        reconnectRetryDelayMs={20}
      />,
    )
    const room = lastRoom()
    await waitFor(() => {
      expect(room.micCalls).toHaveLength(1)
    })

    room.state = lk.ConnectionState.Disconnected
    room.emit(lk.RoomEvent.Disconnected)

    // индикация потери связи (T060): понятный статус с первой потери
    expect(await screen.findByText('Соединение потеряно — переподключение…')).toBeVisible()
    await waitFor(() => {
      expect(refreshTicket.mock.calls.length).toBeGreaterThanOrEqual(1)
    })
  })

  it('истечение reconnect-window — контролируемое завершение «lost», ретраи минта прекращаются', async () => {
    const refreshTicket = vi.fn().mockResolvedValue(null)
    render(
      <ConferenceRoom
        conferenceId="c-1"
        ticket={ticket('jwt-1')}
        mode="video"
        refreshTicket={refreshTicket}
        reconnectWindowMs={150}
        reconnectRetryDelayMs={20}
      />,
    )
    const room = lastRoom()
    await waitFor(() => {
      expect(room.micCalls).toHaveLength(1)
    })

    room.state = lk.ConnectionState.Disconnected
    room.emit(lk.RoomEvent.Disconnected)
    expect(await screen.findByText('Соединение потеряно — переподключение…')).toBeVisible()

    // окно истекло — понятное сообщение контролируемого завершения
    const lost = await screen.findByText(
      'Не удалось восстановить связь с конференцией',
      {},
      { timeout: 2_000 },
    )
    expect(lost).toBeVisible()

    // цепочка остановлена: новых вызовов минта после «lost» нет
    const callsAtLoss = refreshTicket.mock.calls.length
    expect(callsAtLoss).toBeGreaterThanOrEqual(1)
    await new Promise((resolve) => {
      setTimeout(resolve, 80)
    })
    expect(refreshTicket.mock.calls).toHaveLength(callsAtLoss)
    expect(room.connectCalls).toHaveLength(1)
  })
})

describe('ConferenceRoom — тост conference.warning (009 US5 T061 ← T056/T060, FR-015 Q16=B)', () => {
  beforeEach(() => {
    lk.FakeRoom.instances.length = 0
    lkc.trackRefs = []
  })

  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it('data-пакет topic conference.warning — тост о лимите длительности, закрытие вручную', async () => {
    render(
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

    // серверный SendData шедулера T056: payload {"type","endsAt"} — ВСЕМ
    // подключённым (включая гостей без SSE), тема conference.warning
    const payload = new TextEncoder().encode(
      JSON.stringify({ type: 'conference.warning', endsAt: '2026-10-17T15:55:00.000Z' }),
    )
    room.emit(lk.RoomEvent.DataReceived, payload, undefined, undefined, 'conference.warning')

    const toast = await screen.findByText('Конференция завершится по лимиту длительности')
    expect(toast).toBeVisible()

    // событие одно — тост живёт до ручного закрытия (до завершения
    // остаётся warning-before-end)
    fireEvent.click(screen.getByRole('button', { name: 'Закрыть' }))
    await waitFor(() => {
      expect(screen.queryByText('Конференция завершится по лимиту длительности')).toBeNull()
    })
  })

  it('чужая тема и битый payload — тост не показывается (нейтральный игнор)', async () => {
    render(
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

    const encode = (value: string) => new TextEncoder().encode(value)
    // чужая тема: payload правильный, но topic не conference.warning
    room.emit(
      lk.RoomEvent.DataReceived,
      encode(JSON.stringify({ type: 'conference.warning' })),
      undefined,
      undefined,
      'chat.typing',
    )
    // битый JSON нужной темы
    room.emit(
      lk.RoomEvent.DataReceived,
      encode('не-json'),
      undefined,
      undefined,
      'conference.warning',
    )
    // чужой payload.type нужной темы
    room.emit(
      lk.RoomEvent.DataReceived,
      encode(JSON.stringify({ type: 'other.event' })),
      undefined,
      undefined,
      'conference.warning',
    )

    await new Promise((resolve) => {
      setTimeout(resolve, 30)
    })
    expect(screen.queryByText(/лимитом длительности/)).toBeNull()
  })
})

describe('ConferenceRoom — пин-раскладка тайлов (009 US1 T074)', () => {
  /** Собственный identity тикета — self-view тайл тоже закрепляем. */
  const OWN_IDENTITY = 'u:11111111-1111-1111-1111-111111111111:conn-1'

  /** Тайл по подписи-имени участника (figcaption). */
  function tileByLabel(name: string): HTMLElement {
    const label = screen.getByText(name, { exact: false })
    const tile = label.closest('figure')
    if (tile === null) {
      throw new Error(`тайл участника «${name}» не найден`)
    }
    return tile
  }

  /** Сетка/полоса участников (region «Участники конференции»). */
  function tilesSection(): HTMLElement {
    return screen.getByRole('region', { name: 'Участники конференции' })
  }

  function renderRoom(): ReturnType<typeof render> {
    return render(
      <ConferenceRoom
        conferenceId="c-1"
        ticket={ticket('jwt-1')}
        mode="video"
        refreshTicket={vi.fn()}
      >
        <fieldset className="conference-controls" data-testid="controls-slot" />
      </ConferenceRoom>,
    )
  }

  /** Пин тайла кнопкой-тумблером «Развернуть» (явный путь, 008). */
  function pinTile(tile: HTMLElement): void {
    fireEvent.click(within(tile).getByRole('button', { name: 'Развернуть' }))
  }

  beforeEach(() => {
    lk.FakeRoom.instances.length = 0
    lkc.trackRefs = []
    lkc.speakers = []
  })

  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it('(а) клик «Развернуть» — закреплённый тайл несёт пин-класс, остальные — компактную разметку', async () => {
    lkc.trackRefs = [
      {
        participant: { identity: 'u:1:a', name: 'alice' },
        source: 'camera',
        publication: { trackSid: 'cam-1' },
      },
      { participant: { identity: 'u:2:b', name: 'bob' }, source: 'camera' },
    ]
    renderRoom()

    const aliceTile = await waitFor(() => tileByLabel('alice'))
    const expand = within(aliceTile).getByRole('button', { name: 'Развернуть' })
    expect(expand).toHaveAttribute('aria-pressed', 'false')
    fireEvent.click(expand)

    // закреплённый тайл — большой на всю медиа-зону (пин-класс), тумблер
    // перевернулся: «Свернуть» + aria-pressed=true
    const pinnedTile = tileByLabel('alice')
    expect(pinnedTile).toHaveClass('conference-tile-pinned')
    const collapse = within(pinnedTile).getByRole('button', { name: 'Свернуть' })
    expect(collapse).toHaveAttribute('aria-pressed', 'true')

    // остальные — компактная полоса; их тумблеры неактивны
    expect(tilesSection()).toHaveClass('conference-tiles-compact')
    const bobTile = tileByLabel('bob')
    expect(bobTile).not.toHaveClass('conference-tile-pinned')
    expect(within(bobTile).getByRole('button', { name: 'Развернуть' })).toHaveAttribute(
      'aria-pressed',
      'false',
    )
  })

  it('(б) повторный клик по закреплённому тайлу — сворачивание в обычную сетку', async () => {
    lkc.trackRefs = [
      { participant: { identity: 'u:1:a', name: 'alice' }, source: 'camera' },
      { participant: { identity: 'u:2:b', name: 'bob' }, source: 'camera' },
    ]
    renderRoom()
    pinTile(await waitFor(() => tileByLabel('alice')))
    expect(tileByLabel('alice')).toHaveClass('conference-tile-pinned')

    fireEvent.click(within(tileByLabel('alice')).getByRole('button', { name: 'Свернуть' }))

    expect(document.querySelector('.conference-tile-pinned')).toBeNull()
    expect(tilesSection().classList.contains('conference-tiles-compact')).toBe(false)
  })

  it('(в) пин другого участника — переключение: закреплён всегда ≤1', async () => {
    lkc.trackRefs = [
      { participant: { identity: 'u:1:a', name: 'alice' }, source: 'camera' },
      { participant: { identity: 'u:2:b', name: 'bob' }, source: 'camera' },
    ]
    renderRoom()
    pinTile(await waitFor(() => tileByLabel('alice')))
    expect(tileByLabel('alice')).toHaveClass('conference-tile-pinned')

    pinTile(tileByLabel('bob'))

    // переключение: закреплён bob, alice вернулась компактной сеткой
    expect(tileByLabel('bob')).toHaveClass('conference-tile-pinned')
    expect(tileByLabel('alice')).not.toHaveClass('conference-tile-pinned')
    expect(document.querySelectorAll('.conference-tile-pinned')).toHaveLength(1)
  })

  it('(г) закреплённый покидает комнату — автоматический возврат обычной сетки', async () => {
    lkc.trackRefs = [
      { participant: { identity: 'u:1:a', name: 'alice' }, source: 'camera' },
      { participant: { identity: 'u:2:b', name: 'bob' }, source: 'camera' },
    ]
    const view = renderRoom()
    pinTile(await waitFor(() => tileByLabel('alice')))
    expect(document.querySelectorAll('.conference-tile-pinned')).toHaveLength(1)

    // alice ушла из useTracks/useParticipants — пин-цель не существует
    lkc.trackRefs = [{ participant: { identity: 'u:2:b', name: 'bob' }, source: 'camera' }]
    view.rerender(
      <ConferenceRoom
        conferenceId="c-1"
        ticket={ticket('jwt-1')}
        mode="video"
        refreshTicket={vi.fn()}
      >
        <fieldset className="conference-controls" data-testid="controls-slot" />
      </ConferenceRoom>,
    )

    await waitFor(() => {
      expect(screen.queryByText('alice')).toBeNull()
    })
    expect(document.querySelector('.conference-tile-pinned')).toBeNull()
    expect(tilesSection().classList.contains('conference-tiles-compact')).toBe(false)
    expect(within(tileByLabel('bob')).getByRole('button', { name: 'Развернуть' })).toHaveAttribute(
      'aria-pressed',
      'false',
    )
  })

  it('(д) собственный тайл и демонстрация экрана закрепляемы; завершение демонстрации сбрасывает пин', async () => {
    lkc.trackRefs = [
      // self-view: собственный тайл (камера выключена — аватар)
      { participant: { identity: OWN_IDENTITY, name: 'alice' }, source: 'camera' },
      { participant: { identity: 'u:2:b', name: 'bob' }, source: 'screen_share' },
    ]
    const view = renderRoom()

    // собственный тайл закрепляем как любой другой
    const ownTile = await waitFor(() => tileByLabel('alice'))
    pinTile(ownTile)
    expect(tileByLabel('alice')).toHaveClass('conference-tile-pinned')
    expect(tileByLabel('alice').querySelector('.avatar')).not.toBeNull()

    // демонстрация экрана закрепляема — переключение с собственного тайла
    const screenLabel = screen.getByText('Экран — bob')
    const screenTile = screenLabel.closest('figure')
    if (screenTile === null) {
      throw new Error('тайл демонстрации экрана не найден')
    }
    pinTile(screenTile)
    const pinnedScreen = document.querySelector('.conference-tile-pinned')
    expect(pinnedScreen).not.toBeNull()
    expect(pinnedScreen).toHaveTextContent('Экран — bob')
    // прежний закреплённый вернулся компактной полосой
    expect(tileByLabel('alice')).not.toHaveClass('conference-tile-pinned')

    // завершение единственной демонстрации у закреплённого — сброс пина
    lkc.trackRefs = [{ participant: { identity: OWN_IDENTITY, name: 'alice' }, source: 'camera' }]
    view.rerender(
      <ConferenceRoom
        conferenceId="c-1"
        ticket={ticket('jwt-1')}
        mode="video"
        refreshTicket={vi.fn()}
      >
        <fieldset className="conference-controls" data-testid="controls-slot" />
      </ConferenceRoom>,
    )
    await waitFor(() => {
      expect(screen.queryByText('Экран — bob')).toBeNull()
    })
    expect(document.querySelector('.conference-tile-pinned')).toBeNull()
    expect(tilesSection().classList.contains('conference-tiles-compact')).toBe(false)
  })

  it('(е) регресс без пина: экранная секция широкой сеткой, аудио-рендер и children не тронуты', async () => {
    lkc.trackRefs = [
      { participant: { identity: 'u:1:a', name: 'alice' }, source: 'camera' },
      { participant: { identity: 'u:2:b', name: 'bob' }, source: 'screen_share' },
    ]
    renderRoom()

    const screens = await waitFor(() => {
      const section = screen.getByRole('region', { name: 'Демонстрации экрана' })
      expect(section).toHaveClass('conference-screens')
      return section
    })
    expect(screens.classList.contains('conference-tiles-compact')).toBe(false)
    expect(tilesSection().classList.contains('conference-tiles-compact')).toBe(false)
    // аудио удалённых участников смонтировано; слот MediaControls/панелей жив
    expect(screen.getByTestId('lk-audio-renderer')).not.toBeNull()
    expect(screen.getByTestId('controls-slot')).not.toBeNull()

    // и в пин-раскладке аудио-рендер и children остаются на месте
    pinTile(tileByLabel('alice'))
    expect(screen.getByTestId('lk-audio-renderer')).not.toBeNull()
    expect(screen.getByTestId('controls-slot')).not.toBeNull()
    // невыключенная чужая демонстрация уходит в компактную полосу
    expect(tilesSection()).toHaveTextContent('Экран — bob')
    expect(screen.queryByRole('region', { name: 'Демонстрации экрана' })).toBeNull()
  })
})
