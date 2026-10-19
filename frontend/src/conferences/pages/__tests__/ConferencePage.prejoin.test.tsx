import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { MockInstance } from 'vitest'
import { stubLocationAssign } from '../../../test/location'
import type { ConferenceView, JoinTicket, MeView } from '../../api/conferences'
import { activeConferenceId, leaveActiveConference } from '../../hooks/useConference'

/**
 * 009, US2, T035 — сквозные сценарии pre-join на уровне экрана
 * ConferencePage (T034): дефолты тумблеров для каждой точки входа
 * (?mode=video кнопки «видеозвонок», ?mode=audio «аудиозвонок», прямая
 * ссылка без mode — дефолты по №45 view.mode; гостевая ссылка — тот же
 * PreJoinScreen c mode='audio' без restrictions, T049), открытый
 * pre-join не минтит №47 и не занимает слот (FR-007/US2-5), «Войти» с
 * обоими тумблерами выкл — вход «только слушаю» (FR-006): №47 один раз,
 * ConferenceRoom получает initialMedia {оба false} — подключение БЕЗ
 * публикации (сама публикация/её отсутствие проверены на FakeRoom в
 * ConferenceRoom.test), «Отмена» — отключение без следов (US2-6):
 * №47 нет, слот свободен, возврат в мессенджер.
 *
 * ConferenceRoom подменён заглушкой с захватом props (поведение комнаты
 * — отдельный сьют ConferenceRoom.test), createLocalVideoTrack подменён —
 * acquisition превью управляем тестом (jsdom без mediaDevices).
 */

const conferences = vi.hoisted(() => ({
  getConference: vi.fn(),
  getConferenceMe: vi.fn(),
  joinConference: vi.fn(),
}))

const lk = vi.hoisted(() => ({
  createLocalVideoTrack: vi.fn(),
}))

/** Захват props ConferencePage → ConferenceRoom (заглушка комнаты). */
const roomProps = vi.hoisted(() => ({
  captures: [] as Array<{
    conferenceId: string
    initialMedia?: { microphone: boolean; camera: boolean }
    initialDevices?: {
      audioInput: string | null
      videoInput: string | null
      audioOutput: string | null
    } | null
  }>,
}))

vi.mock('../../api/conferences', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../api/conferences')>()),
  getConference: conferences.getConference,
  getConferenceMe: conferences.getConferenceMe,
  joinConference: conferences.joinConference,
}))

vi.mock('livekit-client', () => ({
  createLocalVideoTrack: lk.createLocalVideoTrack,
  RoomEvent: { ActiveDeviceChanged: 'activeDeviceChanged' },
}))

vi.mock('../../components/ConferenceRoom', () => ({
  default: (props: {
    conferenceId: string
    initialMedia?: { microphone: boolean; camera: boolean }
    initialDevices?: {
      audioInput: string | null
      videoInput: string | null
      audioOutput: string | null
    } | null
  }) => {
    roomProps.captures.push({
      conferenceId: props.conferenceId,
      initialMedia: props.initialMedia,
      initialDevices: props.initialDevices,
    })
    return <div data-testid="conference-room" />
  },
}))

import ConferencePage from '../ConferencePage'

const ME = '11111111-1111-1111-1111-111111111111'
const CONFERENCE_ID = 'aaaaaaaa-0000-4000-8000-000000000001'

function conferenceView(overrides: Partial<ConferenceView> = {}): ConferenceView {
  return {
    id: CONFERENCE_ID,
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

function meView(overrides: Partial<MeView> = {}): MeView {
  return {
    role: 'participant',
    micRestricted: false,
    cameraRestricted: false,
    screenRestricted: false,
    speechRestricted: false,
    ...overrides,
  }
}

function joinTicket(overrides: Partial<JoinTicket> = {}): JoinTicket {
  return {
    token: 'jwt-1',
    wsUrl: 'wss://media.example.com',
    identity: `u:${ME}:conn-1`,
    name: 'alice',
    expiresAt: '2026-10-17T12:15:00.000Z',
    ...overrides,
  }
}

/** Точка входа: search маршрута до монтажа (entryMode фиксируется на монтаж, T034). */
function openAt(search: string, view: ConferenceView = conferenceView()): void {
  window.history.pushState({}, '', `/conference/${CONFERENCE_ID}${search}`)
  conferences.getConference.mockResolvedValue(view)
  conferences.getConferenceMe.mockResolvedValue(meView())
  render(<ConferencePage conferenceId={CONFERENCE_ID} />)
}

function micToggle(): HTMLElement {
  return screen.getByRole('button', { name: 'Микрофон' })
}

function cameraToggle(): HTMLElement {
  return screen.getByRole('button', { name: 'Камера' })
}

describe('ConferencePage pre-join: точки входа и стартовая выборка (009 US2 T034/T035)', () => {
  let assignMock: MockInstance

  beforeEach(() => {
    conferences.getConference.mockReset()
    conferences.getConferenceMe.mockReset()
    conferences.joinConference.mockReset()
    lk.createLocalVideoTrack.mockReset()
    lk.createLocalVideoTrack.mockResolvedValue({
      stop: vi.fn(),
      attach: vi.fn(),
      detach: vi.fn(),
      setDeviceId: vi.fn(() => Promise.resolve()),
    })
    roomProps.captures.length = 0
    assignMock = stubLocationAssign()
  })

  afterEach(() => {
    cleanup()
    leaveActiveConference()
    assignMock.mockRestore()
    window.history.pushState({}, '', '/')
  })

  it('?mode=video (кнопка «видеозвонок»): превью камеры запрашивается сразу (камера+микрофон)', async () => {
    openAt('?mode=video')

    expect(await screen.findByRole('button', { name: 'Войти' })).toBeInTheDocument()
    expect(micToggle()).toHaveAttribute('aria-pressed', 'true')
    await waitFor(() => {
      expect(lk.createLocalVideoTrack).toHaveBeenCalledTimes(1)
    })
    await waitFor(() => {
      expect(cameraToggle()).toHaveAttribute('aria-pressed', 'true')
    })
  })

  it('?mode=audio (кнопка «аудиозвонок»): только микрофон — приоритет точки входа над №45', async () => {
    // №45 mode=video, но точка входа аудиозвонок — дефолты по mode входа (FR-005)
    openAt('?mode=audio', conferenceView({ mode: 'video' }))

    expect(await screen.findByRole('button', { name: 'Войти' })).toBeInTheDocument()
    expect(micToggle()).toHaveAttribute('aria-pressed', 'true')
    expect(lk.createLocalVideoTrack).not.toHaveBeenCalled()
    expect(cameraToggle()).toHaveAttribute('aria-pressed', 'false')
  })

  it('прямая ссылка без mode: дефолты по №45 view.mode (audio → только микрофон)', async () => {
    openAt('', conferenceView({ mode: 'audio' }))

    expect(await screen.findByRole('button', { name: 'Войти' })).toBeInTheDocument()
    expect(micToggle()).toHaveAttribute('aria-pressed', 'true')
    expect(lk.createLocalVideoTrack).not.toHaveBeenCalled()
    expect(cameraToggle()).toHaveAttribute('aria-pressed', 'false')
  })

  it('открытый pre-join не минтит №47 и не занимает слот (FR-007, US2-5)', async () => {
    openAt('?mode=video')

    expect(await screen.findByRole('button', { name: 'Войти' })).toBeInTheDocument()
    await waitFor(() => {
      expect(lk.createLocalVideoTrack).toHaveBeenCalledTimes(1)
    })

    expect(conferences.joinConference).not.toHaveBeenCalled()
    expect(activeConferenceId()).toBeNull()
  })

  it('«Войти» с обоими выкл — «только слушаю»: №47 один раз, комнате уходит initialMedia без публикации (FR-006)', async () => {
    openAt('?mode=audio')
    conferences.joinConference.mockResolvedValue(joinTicket())

    expect(await screen.findByRole('button', { name: 'Войти' })).toBeInTheDocument()
    // выключаем единственный включённый тумблер (камера уже выкл — mode=audio)
    fireEvent.click(micToggle())
    expect(micToggle()).toHaveAttribute('aria-pressed', 'false')
    expect(cameraToggle()).toHaveAttribute('aria-pressed', 'false')

    fireEvent.click(screen.getByRole('button', { name: 'Войти' }))

    expect(await screen.findByTestId('conference-room')).toBeInTheDocument()
    expect(conferences.joinConference).toHaveBeenCalledTimes(1)
    expect(conferences.joinConference).toHaveBeenCalledWith(CONFERENCE_ID)
    expect(roomProps.captures).toEqual([
      {
        conferenceId: CONFERENCE_ID,
        initialMedia: { microphone: false, camera: false },
        initialDevices: { audioInput: null, videoInput: null, audioOutput: null },
      },
    ])
    expect(activeConferenceId()).toBe(CONFERENCE_ID)
  })

  it('«Отмена» — отключение без следов: №47 нет, слот свободен, возврат в мессенджер (US2-6)', async () => {
    openAt('?mode=video')

    expect(await screen.findByRole('button', { name: 'Войти' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Отмена' }))

    expect(assignMock).toHaveBeenCalledWith('/')
    expect(conferences.joinConference).not.toHaveBeenCalled()
    expect(activeConferenceId()).toBeNull()
  })
})
