import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ConferenceMode } from '../../../conferences/api/conferences'
import { ChatHeader } from '../ChatHeader'

/**
 * 009, US1, T029 — кнопки «видеозвонок»/«аудиозвонок» ChatHeader (T023,
 * FR-001): пара живёт в любом чате (direct и группа), клики уходят
 * страницей onStartCall(mode); звонок — не тумблер: нет aria-pressed и
 * спецсостояний (в отличие от лупы/колокола). Позиция пары в .ch-btns
 * покрыта ChatHeader.test/ChatHeader.search.test — здесь провод клика.
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

function groupChat() {
  return {
    kind: 'group' as const,
    title: 'Группа',
    memberCount: 3,
    members: [],
    myRole: 'owner' as const,
    meUserId: '11111111-1111-1111-1111-111111111111',
    meUsername: 'bob',
  }
}

function expectClickFiresStartCall(
  chat: ReturnType<typeof directChat> | ReturnType<typeof groupChat>,
): void {
  const onStartCall = vi.fn<(mode: ConferenceMode) => void>()
  render(<ChatHeader chat={chat} onStartCall={onStartCall} />)

  const video = screen.getByRole('button', { name: 'Видеозвонок' })
  const audio = screen.getByRole('button', { name: 'Аудиозвонок' })
  // звонок — не тумблер: состояний включённости нет
  expect(video).not.toHaveAttribute('aria-pressed')
  expect(audio).not.toHaveAttribute('aria-pressed')

  fireEvent.click(video)
  expect(onStartCall).toHaveBeenCalledTimes(1)
  expect(onStartCall).toHaveBeenCalledWith('video')

  fireEvent.click(audio)
  expect(onStartCall).toHaveBeenCalledTimes(2)
  expect(onStartCall).toHaveBeenLastCalledWith('audio')
}

describe('ChatHeader кнопки звонка (009 US1 T023/T029)', () => {
  afterEach(cleanup)

  it('direct-чат: клики «Видеозвонок»/«Аудиозвонок» → onStartCall(video|audio)', () => {
    expectClickFiresStartCall(directChat())
  })

  it('групповой чат: та же пара кнопок и тот же провод (FR-001 покрывает группы)', () => {
    expectClickFiresStartCall(groupChat())
  })

  it('без обработчика кнопки доступны и молчат (заголовок — проекция)', () => {
    render(<ChatHeader chat={directChat()} />)
    expect(() => {
      fireEvent.click(screen.getByRole('button', { name: 'Видеозвонок' }))
      fireEvent.click(screen.getByRole('button', { name: 'Аудиозвонок' }))
    }).not.toThrow()
  })
})
