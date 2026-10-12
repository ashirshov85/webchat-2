import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { Problem } from '../../../api/auth'
import { ToastProvider } from '../../../ui/Toast'
import { PasswordChangeForm } from '../PasswordChangeForm'

/**
 * Форма смены пароля в «Мой профиль» (008b US2, T012 — тесты до реализации
 * T022; FR-004/FR-005, contracts §4.3, research §8): отдельная <form> с полями
 * текущий/новый/подтверждение (autoComplete current-password / new-password);
 * клиентская валидация validateNewPassword блокирует отправку — запроса нет
 * вовсе; успех → setTokenPair (единая точка ротации, FR-008) + один toast +
 * очистка полей; 400 — inline .modal-err role="alert" через problemMessage,
 * без toast (паттерн ProfileModal). Топ-лист слабых паролей — серверная
 * проверка: 400 newPassword: too_common рендерится понятным сообщением.
 */
const { mockChangePassword, mockSetTokenPair } = vi.hoisted(() => ({
  mockChangePassword: vi.fn(),
  mockSetTokenPair: vi.fn(),
}))

vi.mock('../../../api/auth', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../api/auth')>()
  return { ...actual, changePassword: mockChangePassword }
})

vi.mock('../../../auth/session', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../auth/session')>()
  return { ...actual, setTokenPair: mockSetTokenPair }
})

/** Демо-«Я» как в ProfileModal.test: зеркало клиентской проверки ≠ username/email. */
const ACCOUNT = {
  username: 'hargrove',
  email: 'h.hargrove@aethergram.io',
}

const CURRENT_PASSWORD = 'old-pass-123'
const NEW_PASSWORD = 'n3w-pass-456'

/** Паритет POST /auth/refresh: новая пара текущей сессии (contract §1). */
function tokenPair() {
  return {
    accessToken: 'access-2',
    refreshToken: 'refresh-2',
    tokenType: 'Bearer',
    expiresInSec: 300,
  }
}

function renderForm() {
  render(
    <ToastProvider>
      <PasswordChangeForm username={ACCOUNT.username} email={ACCOUNT.email} />
    </ToastProvider>,
  )
}

function fillPasswordForm(current: string, next: string, confirm: string) {
  fireEvent.change(screen.getByLabelText('Текущий пароль'), { target: { value: current } })
  fireEvent.change(screen.getByLabelText('Новый пароль'), { target: { value: next } })
  fireEvent.change(screen.getByLabelText('Повторите новый пароль'), {
    target: { value: confirm },
  })
  fireEvent.click(screen.getByRole('button', { name: 'Сменить пароль' }))
}

/** Тост-слот пуст: ошибка формы не рождает тост (FR-025, паттерн ProfileModal). */
function expectNoToast() {
  expect(screen.queryByRole('status')?.textContent).toBe('')
}

afterEach(() => {
  cleanup()
  vi.resetAllMocks()
})

describe('PasswordChangeForm', () => {
  it('renders current/new/confirm fields with password-manager autocomplete hints', () => {
    renderForm()

    expect(screen.getByLabelText('Текущий пароль')).toHaveAttribute(
      'autocomplete',
      'current-password',
    )
    expect(screen.getByLabelText('Новый пароль')).toHaveAttribute('autocomplete', 'new-password')
    expect(screen.getByLabelText('Повторите новый пароль')).toHaveAttribute(
      'autocomplete',
      'new-password',
    )
    expect(screen.getByRole('button', { name: 'Сменить пароль' })).toHaveAttribute('type', 'submit')
  })

  it.each([
    ['несовпадающее подтверждение', 'Str0ng!2', 'Str0ng!3'],
    ['длину меньше 8 символов', 'Sh0rt!1', 'Sh0rt!1'],
    ['пароль из одних пробелов', '        ', '        '],
    ['совпадение с username', 'hargrove', 'hargrove'],
    [
      'совпадение с email без учёта регистра',
      'H.HARGROVE@AETHERGRAM.IO',
      'H.HARGROVE@AETHERGRAM.IO',
    ],
  ])('client validation blocks submit for %s: no request, inline alert', async (_r, next, c) => {
    mockChangePassword.mockResolvedValueOnce(tokenPair())
    renderForm()

    fillPasswordForm(CURRENT_PASSWORD, next, c)

    expect(await screen.findByRole('alert')).toBeVisible()
    expect(mockChangePassword).not.toHaveBeenCalled()
    expect(mockSetTokenPair).not.toHaveBeenCalled()
    expectNoToast()
  })

  it('submits exactly {currentPassword, newPassword}, applies the new pair, toasts, clears fields', async () => {
    mockChangePassword.mockResolvedValueOnce(tokenPair())
    renderForm()

    fillPasswordForm(CURRENT_PASSWORD, NEW_PASSWORD, NEW_PASSWORD)

    await waitFor(() => expect(mockChangePassword).toHaveBeenCalledTimes(1))
    // confirmPassword — только клиентская проверка, в API поля нет (contract §1)
    expect(mockChangePassword).toHaveBeenCalledWith({
      currentPassword: CURRENT_PASSWORD,
      newPassword: NEW_PASSWORD,
    })
    // единая точка ротации: пользователь остаётся вошедшим (FR-008)
    expect(mockSetTokenPair).toHaveBeenCalledTimes(1)
    expect(mockSetTokenPair).toHaveBeenCalledWith(
      expect.objectContaining({ accessToken: 'access-2', refreshToken: 'refresh-2' }),
    )
    expect(await screen.findByText('Пароль изменён')).toBeVisible()
    expect(screen.getByLabelText('Текущий пароль')).toHaveValue('')
    expect(screen.getByLabelText('Новый пароль')).toHaveValue('')
    expect(screen.getByLabelText('Повторите новый пароль')).toHaveValue('')
  })

  it('400 currentPassword:invalid → inline alert via problemMessage, no toast, fields kept', async () => {
    mockChangePassword.mockRejectedValueOnce({
      title: 'Bad Request',
      status: 400,
      errors: { currentPassword: ['invalid'] },
    } satisfies Problem)
    renderForm()

    fillPasswordForm(CURRENT_PASSWORD, NEW_PASSWORD, NEW_PASSWORD)

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('currentPassword: invalid')
    expectNoToast()
    expect(mockSetTokenPair).not.toHaveBeenCalled()
    // значения сохранены для повтора (паттерн .modal-err ProfileModal)
    expect(screen.getByLabelText('Текущий пароль')).toHaveValue(CURRENT_PASSWORD)
    expect(screen.getByLabelText('Новый пароль')).toHaveValue(NEW_PASSWORD)
  })

  it('server-side too_common policy miss renders an understandable problemMessage', async () => {
    mockChangePassword.mockRejectedValueOnce({
      title: 'Bad Request',
      status: 400,
      errors: { newPassword: ['too_common'] },
    } satisfies Problem)
    renderForm()

    // клиентские проверки проходят: топ-лист слабых — серверная проверка (research §8)
    fillPasswordForm(CURRENT_PASSWORD, 'password123', 'password123')

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('newPassword: too_common')
    expectNoToast()
    expect(mockSetTokenPair).not.toHaveBeenCalled()
  })
})
