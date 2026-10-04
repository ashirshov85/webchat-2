import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { getCurrentUser } from '../../../api/auth'
import { fetchPresenceSettings, updatePresenceSettings } from '../../../presence/presenceApi'
import { ToastProvider } from '../../../ui/Toast'
import { ProfileModal } from '../ProfileModal'

/**
 * Модальная форма «Мой профиль» (feature 008, US2, T033; FR-015,
 * ui-behavior §3, research §D): проекция #profileForm прототипа
 * specs/008-chat-window-styling/design/chats.html БЕЗ редактирования
 * имени (008a — вне фичи): username/email — readonly-строки .modal-ro
 * из `GET /users/me` (не поля ввода), переключатель «Режим инкогнито» —
 * семантика и API 007 (№38 GET отражает сохранённый режим, PUT
 * применяет), Сохранить → PUT → ровно один тост (FR-025).
 *
 * Что сохранено из 007 (PresenceSettingsPage, FR-034): №38 GET на
 * монтировании отражает персистентный режим; PUT идемпотентен и
 * возвращает сохранённое значение; сбой PUT — problemMessage, чекбокс
 * возвращается к серверному значению. Тесты написаны ДО реализации
 * (конституция VI) — красные до ProfileModal.tsx.
 */

const { mockGetCurrentUser, mockFetchPresenceSettings, mockUpdatePresenceSettings } = vi.hoisted(
  () => ({
    mockGetCurrentUser: vi.fn(),
    mockFetchPresenceSettings: vi.fn(),
    mockUpdatePresenceSettings: vi.fn(),
  }),
)

vi.mock('../../../api/auth', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../api/auth')>()
  return { ...actual, getCurrentUser: mockGetCurrentUser }
})

vi.mock('../../../presence/presenceApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../presence/presenceApi')>()
  return {
    ...actual,
    fetchPresenceSettings: mockFetchPresenceSettings,
    updatePresenceSettings: mockUpdatePresenceSettings,
  }
})

const mockGetCurrentUser_ = vi.mocked(getCurrentUser)
const mockFetch = vi.mocked(fetchPresenceSettings)
const mockUpdate = vi.mocked(updatePresenceSettings)

/** Демо-«Я» прототипа: username/email из GET /users/me. */
function meUser(overrides: Partial<{ username: string; email: string }> = {}) {
  return {
    id: '11111111-1111-1111-1111-111111111111',
    username: 'hargrove',
    email: 'h.hargrove@aethergram.io',
    status: 'active' as const,
    createdAt: '2026-09-01T00:00:00.000Z',
    ...overrides,
  }
}

function renderModal() {
  const onClose = vi.fn()
  render(
    <ToastProvider>
      <ProfileModal onClose={onClose} />
    </ToastProvider>,
  )
  return { onClose }
}

/** Переключатель «Режим инкогнито» (.tgl-row прототипа). */
function incognitoToggle(): HTMLElement {
  return screen.getByRole('checkbox', { name: /Режим инкогнито/ })
}

afterEach(() => {
  cleanup()
  vi.resetAllMocks()
})

describe('ProfileModal', () => {
  it('shows username and email from GET /users/me as readonly rows (no inputs)', async () => {
    mockGetCurrentUser_.mockResolvedValueOnce(meUser())
    mockFetch.mockResolvedValueOnce({ incognito: false })
    renderModal()

    expect(await screen.findByText('hargrove')).toBeVisible()
    expect(screen.getByText('h.hargrove@aethergram.io')).toBeVisible()
    // readonly: значения — строки .modal-ro, а не редактируемые поля (FR-015)
    expect(screen.queryByRole('textbox')).toBeNull()
    expect(screen.getByText('username')).toBeVisible()
    expect(screen.getByText('email')).toBeVisible()
  })

  it('renders «—» when email is empty (prototype fallback)', async () => {
    mockGetCurrentUser_.mockResolvedValueOnce(meUser({ email: '' }))
    mockFetch.mockResolvedValueOnce({ incognito: false })
    renderModal()

    expect(await screen.findByText('hargrove')).toBeVisible()
    expect(screen.getByText('—')).toBeVisible()
  })

  it('reflects the persisted №38 incognito mode on mount', async () => {
    mockGetCurrentUser_.mockResolvedValueOnce(meUser())
    mockFetch.mockResolvedValueOnce({ incognito: true })
    renderModal()

    expect(await waitFor(() => expect(incognitoToggle()).toBeChecked()))
    expect(mockFetch).toHaveBeenCalledTimes(1)
  })

  it('PUTs the toggled mode, toasts once and closes (prototype profileForm submit)', async () => {
    mockGetCurrentUser_.mockResolvedValueOnce(meUser())
    mockFetch.mockResolvedValueOnce({ incognito: false })
    mockUpdate.mockResolvedValueOnce({ incognito: true })
    const { onClose } = renderModal()

    await screen.findByText('hargrove')
    fireEvent.click(incognitoToggle())
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить' }))

    await waitFor(() => expect(mockUpdate).toHaveBeenCalledTimes(1))
    expect(mockUpdate).toHaveBeenCalledWith(true)
    expect(
      await screen.findByText('Профиль обновлён — hargrove · инкогнито: вы offline для всех'),
    ).toBeVisible()
    await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1))
    expect(mockUpdate).toHaveBeenCalledTimes(1)
  })

  it('saves the unchanged off mode: PUT false, toast without the incognito suffix', async () => {
    mockGetCurrentUser_.mockResolvedValueOnce(meUser())
    mockFetch.mockResolvedValueOnce({ incognito: false })
    mockUpdate.mockResolvedValueOnce({ incognito: false })
    const { onClose } = renderModal()

    await screen.findByText('hargrove')
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить' }))

    await waitFor(() => expect(mockUpdate).toHaveBeenCalledWith(false))
    expect(await screen.findByText('Профиль обновлён — hargrove')).toBeVisible()
    await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1))
  })

  it('PUT failure: modal error, no toast, no close, checkbox reverts to the server value', async () => {
    mockGetCurrentUser_.mockResolvedValueOnce(meUser())
    mockFetch.mockResolvedValueOnce({ incognito: false })
    mockUpdate.mockRejectedValueOnce({ title: 'Too Many Requests', status: 429 })
    const { onClose } = renderModal()

    await screen.findByText('hargrove')
    fireEvent.click(incognitoToggle())
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить' }))

    expect(await screen.findByText('Too Many Requests')).toBeVisible()
    expect(await waitFor(() => expect(incognitoToggle()).not.toBeChecked()))
    expect(screen.queryByRole('status')?.textContent).toBe('')
    expect(onClose).not.toHaveBeenCalled()
  })

  it('load failure shows the error with «Повторить» and refetches both sources', async () => {
    mockGetCurrentUser_.mockRejectedValueOnce({ title: 'Internal Server Error', status: 500 })
    mockFetch.mockRejectedValueOnce({ title: 'Internal Server Error', status: 500 })
    renderModal()

    expect(await screen.findByText('Internal Server Error')).toBeVisible()
    mockGetCurrentUser_.mockResolvedValueOnce(meUser())
    mockFetch.mockResolvedValueOnce({ incognito: true })
    fireEvent.click(screen.getByRole('button', { name: 'Повторить' }))

    expect(await screen.findByText('hargrove')).toBeVisible()
    expect(await waitFor(() => expect(incognitoToggle()).toBeChecked()))
  })

  it('«Отмена» closes without PUT and without toast', async () => {
    mockGetCurrentUser_.mockResolvedValueOnce(meUser())
    mockFetch.mockResolvedValueOnce({ incognito: false })
    const { onClose } = renderModal()

    await screen.findByText('hargrove')
    fireEvent.click(screen.getByRole('button', { name: 'Отмена' }))

    expect(onClose).toHaveBeenCalledTimes(1)
    expect(mockUpdate).not.toHaveBeenCalled()
    expect(screen.queryByRole('status')?.textContent).toBe('')
  })
})
