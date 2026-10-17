import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { MockInstance } from 'vitest'
import type { Message } from '../../../api/chats'
import type { ConferenceView } from '../../../conferences/api/conferences'
import { stubLocationAssign } from '../../../test/location'
import { formatTime } from '../../../ui/time'
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
    conferences.getConference.mockReset()
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
    conferences.getConference.mockResolvedValue(conferenceView({ status: 'ended' }))
    renderFeed([systemMessage(invitePayload())])
    fireEvent.click(screen.getByRole('button', { name: 'Подключиться' }))
    await waitFor(() => {
      expect(screen.getByText('Конференция завершена')).toBeTruthy()
    })
    expect(assignSpy).not.toHaveBeenCalled()
    expect(screen.queryByRole('button')).toBeNull()
  })

  it('транспортный сбой №45 → «Не удалось подключиться», кнопка остаётся для повтора', async () => {
    conferences.getConference
      .mockRejectedValueOnce(new Error('network'))
      .mockResolvedValueOnce(conferenceView())
    renderFeed([systemMessage(invitePayload())])
    fireEvent.click(screen.getByRole('button', { name: 'Подключиться' }))
    await waitFor(() => {
      expect(screen.getByText('Не удалось подключиться')).toBeTruthy()
    })
    expect(assignSpy).not.toHaveBeenCalled()
    // повторный клик — повторная проверка №45 и переход
    fireEvent.click(screen.getByRole('button', { name: 'Подключиться' }))
    await waitFor(() => {
      expect(assignSpy).toHaveBeenCalledWith('/conference/c-1?mode=video')
    })
  })

  it('единый 404 conference_not_found → «Конференция недоступна», без деталей', async () => {
    conferences.getConference.mockRejectedValue({
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
