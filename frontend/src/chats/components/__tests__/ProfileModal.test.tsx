import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { getCurrentUser } from '../../../api/auth'
import { updateProfile } from '../../../api/chats'
import { fetchPresenceSettings, updatePresenceSettings } from '../../../presence/presenceApi'
import { ToastProvider } from '../../../ui/Toast'
import { ProfileModal } from '../ProfileModal'

/**
 * Модальная форма «Мой профиль» (feature 008, US2, T033; FR-015,
 * ui-behavior §3, research §D; 008a US1, T021; ui-behavior §1.1):
 * проекция #profileForm прототипа chats.html С полем «Имя» (008a
 * FR-001): placeholder = username, maxlength 64, предзаполнено
 * displayName из №10; username/email — readonly-строки .modal-ro из
 * `GET /users/me`, переключатель «Режим инкогнито» — семантика и API
 * 007 (№38 GET отражает сохранённый режим, PUT применяет), Сохранить →
 * №39 PUT (имя: очищенное поле — явный null; пробельная строка —
 * клиентская ошибка без запроса) → №38 PUT → ровно один тост (FR-025).
 *
 * Что сохранено из 007 (PresenceSettingsPage, FR-034): №38 GET на
 * монтировании отражает персистентный режим; PUT идемпотентен и
 * возвращает сохранённое значение; сбой PUT — problemMessage, чекбокс
 * возвращается к серверному значению.
 */
const {
  mockGetCurrentUser,
  mockUpdateProfile,
  mockFetchPresenceSettings,
  mockUpdatePresenceSettings,
} = vi.hoisted(() => ({
  mockGetCurrentUser: vi.fn(),
  mockUpdateProfile: vi.fn(),
  mockFetchPresenceSettings: vi.fn(),
  mockUpdatePresenceSettings: vi.fn(),
}))

vi.mock('../../../api/auth', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../api/auth')>()
  return { ...actual, getCurrentUser: mockGetCurrentUser }
})

vi.mock('../../../api/chats', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../api/chats')>()
  return { ...actual, updateProfile: mockUpdateProfile }
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
const mockUpdateProfile_ = vi.mocked(updateProfile)
const mockFetch = vi.mocked(fetchPresenceSettings)
const mockUpdate = vi.mocked(updatePresenceSettings)

/** Демо-«Я» прототипа: username/email/displayName из GET /users/me (№10). */
function meUser(
  overrides: Partial<{ username: string; email: string; displayName?: string }> = {},
) {
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
  it('shows the «Имя» field (placeholder = username, maxlength 64) and prefills displayName from №10', async () => {
    mockGetCurrentUser_.mockResolvedValueOnce(meUser({ displayName: 'Мария' }))
    mockFetch.mockResolvedValueOnce({ incognito: false })
    renderModal()

    const nameField = await screen.findByRole('textbox', { name: 'Имя' })
    expect(nameField).toHaveValue('Мария')
    expect(nameField).toHaveAttribute('maxlength', '64')
    // имени нет — поле пусто и подсказывает username (ui-behavior §1.1)
    expect(nameField).toHaveAttribute('placeholder', 'hargrove')
  })

  it('shows username and email from GET /users/me as readonly rows (no inputs besides «Имя»)', async () => {
    mockGetCurrentUser_.mockResolvedValueOnce(meUser())
    mockFetch.mockResolvedValueOnce({ incognito: false })
    renderModal()

    expect(await screen.findByText('hargrove')).toBeVisible()
    expect(screen.getByText('h.hargrove@aethergram.io')).toBeVisible()
    // readonly: значения — строки .modal-ro, а не редактируемые поля (FR-015)
    expect(screen.getAllByRole('textbox')).toHaveLength(1)
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

    await waitFor(() => expect(incognitoToggle()).toBeChecked())
    expect(mockFetch).toHaveBeenCalledTimes(1)
  })

  it('saves №39 (name → null when cleared) and №38, toasts once with the chain name and closes', async () => {
    mockGetCurrentUser_.mockResolvedValueOnce(meUser({ displayName: 'Мария' }))
    mockFetch.mockResolvedValueOnce({ incognito: false })
    mockUpdateProfile_.mockResolvedValueOnce(meUser())
    mockUpdate.mockResolvedValueOnce({ incognito: true })
    const { onClose } = renderModal()

    const nameField = await screen.findByRole('textbox', { name: 'Имя' })
    // полностью очищенное поле = явный null (сброс к username, FR-001)
    fireEvent.change(nameField, { target: { value: '' } })
    fireEvent.click(incognitoToggle())
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить' }))

    await waitFor(() => expect(mockUpdateProfile).toHaveBeenCalledTimes(1))
    expect(mockUpdateProfile).toHaveBeenCalledWith({ displayName: null })
    await waitFor(() => expect(mockUpdate).toHaveBeenCalledTimes(1))
    expect(mockUpdate).toHaveBeenCalledWith(true)
    expect(
      await screen.findByText('Профиль обновлён — hargrove · инкогнито: вы offline для всех'),
    ).toBeVisible()
    await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1))
    expect(mockUpdate).toHaveBeenCalledTimes(1)
  })

  it('sends the edited name (№39) and toasts it before username (prototype submit)', async () => {
    mockGetCurrentUser_.mockResolvedValueOnce(meUser())
    mockFetch.mockResolvedValueOnce({ incognito: false })
    mockUpdateProfile_.mockResolvedValueOnce(meUser({ displayName: 'Мария' }))
    mockUpdate.mockResolvedValueOnce({ incognito: false })
    const { onClose } = renderModal()

    const nameField = await screen.findByRole('textbox', { name: 'Имя' })
    fireEvent.change(nameField, { target: { value: 'Мария' } })
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить' }))

    await waitFor(() => expect(mockUpdateProfile).toHaveBeenCalledWith({ displayName: 'Мария' }))
    await waitFor(() => expect(mockUpdate).toHaveBeenCalledWith(false))
    expect(await screen.findByText('Профиль обновлён — Мария')).toBeVisible()
    await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1))
  })

  it('whitespace-only name: client validation error, no request at all (mirrors 400 invalid_display_name)', async () => {
    mockGetCurrentUser_.mockResolvedValueOnce(meUser({ displayName: 'Мария' }))
    mockFetch.mockResolvedValueOnce({ incognito: false })
    renderModal()

    const nameField = await screen.findByRole('textbox', { name: 'Имя' })
    fireEvent.change(nameField, { target: { value: '   ' } })
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить' }))

    expect(await screen.findByText('Укажите имя без пробелов в начале и конце')).toBeVisible()
    // прежнее имя сохраняется: ни №39, ни №38, ни тоста, ни закрытия
    expect(mockUpdateProfile).not.toHaveBeenCalled()
    expect(mockUpdate).not.toHaveBeenCalled()
    expect(screen.queryByRole('status')?.textContent).toBe('')
    expect(nameField).toHaveValue('   ')
  })

  it('saves the unchanged off mode: PUT false, toast without the incognito suffix', async () => {
    mockGetCurrentUser_.mockResolvedValueOnce(meUser())
    mockUpdateProfile_.mockResolvedValueOnce(meUser())
    mockFetch.mockResolvedValueOnce({ incognito: false })
    mockUpdate.mockResolvedValueOnce({ incognito: false })
    const { onClose } = renderModal()

    await screen.findByText('hargrove')
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить' }))

    await waitFor(() => expect(mockUpdate).toHaveBeenCalledWith(false))
    expect(await screen.findByText('Профиль обновлён — hargrove')).toBeVisible()
    await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1))
  })

  it('№39 failure: modal error, no №38, no toast, no close', async () => {
    mockGetCurrentUser_.mockResolvedValueOnce(meUser())
    mockFetch.mockResolvedValueOnce({ incognito: false })
    mockUpdateProfile_.mockRejectedValueOnce({ title: 'Too Many Requests', status: 429 })
    const { onClose } = renderModal()

    await screen.findByText('hargrove')
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить' }))

    expect(await screen.findByText('Too Many Requests')).toBeVisible()
    expect(mockUpdate).not.toHaveBeenCalled()
    expect(screen.queryByRole('status')?.textContent).toBe('')
    expect(onClose).not.toHaveBeenCalled()
  })

  it('№38 failure: modal error, no toast, no close, checkbox reverts to the server value', async () => {
    mockGetCurrentUser_.mockResolvedValueOnce(meUser())
    mockFetch.mockResolvedValueOnce({ incognito: false })
    mockUpdateProfile_.mockResolvedValueOnce(meUser())
    mockUpdate.mockRejectedValueOnce({ title: 'Too Many Requests', status: 429 })
    const { onClose } = renderModal()

    await screen.findByText('hargrove')
    fireEvent.click(incognitoToggle())
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить' }))

    expect(await screen.findByText('Too Many Requests')).toBeVisible()
    await waitFor(() => expect(incognitoToggle()).not.toBeChecked())
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
    await waitFor(() => expect(incognitoToggle()).toBeChecked())
  })

  it('«Отмена» closes without PUT and without toast', async () => {
    mockGetCurrentUser_.mockResolvedValueOnce(meUser())
    mockFetch.mockResolvedValueOnce({ incognito: false })
    const { onClose } = renderModal()

    await screen.findByText('hargrove')
    fireEvent.click(screen.getByRole('button', { name: 'Отмена' }))

    expect(onClose).toHaveBeenCalledTimes(1)
    expect(mockUpdateProfile).not.toHaveBeenCalled()
    expect(mockUpdate).not.toHaveBeenCalled()
    expect(screen.queryByRole('status')?.textContent).toBe('')
  })

  /**
   * Экранирование displayName (008a T024, US1 AC5, 008 FR-033): имя —
   * пользовательский ввод; HTML-вставка рендерится как ТЕКСТ (значение
   * поля, тост), не как разметка — dangerouslySetInnerHTML не вводится.
   */
  describe('экранирование «Имени» (008 FR-033)', () => {
    const XSS_IMG = '<img src=x onerror="alert(1)">'

    it('displayName с HTML-вставкой — значение поля это literal-текст, инъекции в DOM нет', async () => {
      mockGetCurrentUser_.mockResolvedValueOnce(meUser({ displayName: XSS_IMG }))
      mockFetch.mockResolvedValueOnce({ incognito: false })
      renderModal()

      const nameField = await screen.findByRole<HTMLInputElement>('textbox', {
        name: 'Имя',
      })
      // Значение input — plain-текст (атрибут value), не разметка
      expect(nameField).toHaveValue(XSS_IMG)
      expect(document.querySelector('img[onerror]')).toBeNull()
    })

    it('тост с HTML-вставкой в имени — literal-текст, инъекции нет', async () => {
      mockGetCurrentUser_.mockResolvedValueOnce(meUser({ displayName: XSS_IMG }))
      mockFetch.mockResolvedValueOnce({ incognito: false })
      mockUpdateProfile_.mockResolvedValueOnce(meUser({ displayName: XSS_IMG }))
      mockUpdate.mockResolvedValueOnce({ incognito: false })
      renderModal()

      await screen.findByRole('textbox', { name: 'Имя' })
      fireEvent.click(screen.getByRole('button', { name: 'Сохранить' }))

      await waitFor(() => expect(mockUpdateProfile).toHaveBeenCalledWith({ displayName: XSS_IMG }))
      expect(await screen.findByText(`Профиль обновлён — ${XSS_IMG}`)).toBeVisible()
      expect(document.querySelector('img[onerror]')).toBeNull()
      expect(document.querySelector('.toast img')).toBeNull()
    })
  })
})
