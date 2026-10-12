import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { Problem, TokenPair } from '../../../api/auth'
import { ToastProvider } from '../../../ui/Toast'
import { PasswordChangeModal } from '../PasswordChangeModal'

/**
 * Модаль «Смена пароля» (008b T046 — вынос инлайн-формы T022/T023 в
 * отдельного жителя единой оболочки, по образцу CreateGroupDialog):
 * поля текущий/новый/подтверждение (autoComplete current-password /
 * new-password), футер .modal-btns «Отмена»/«Сменить пароль»;
 * отправляется ровно {currentPassword, newPassword}; клиентская
 * валидация ДО запроса с русскими текстами; ЛОКАЛЬНЫЙ маппинг problem →
 * русские сообщения (глобальный problemMessage не участвует): 400
 * currentPassword: invalid → «Текущий пароль неверен»; 400 newPassword
 * коды политики; 429 + retryAfterSec → склонение минут/часов; 401 →
 * «Сессия истекла»; сеть/unknown → русский fallback; успех →
 * setTokenPair + один toast «Пароль изменён» + закрытие (onSuccess);
 * «Отмена» — без запроса; все кнопки disabled на время отправки; строка
 * ошибки .modal-err постоянная (min-height держит компоновку),
 * role=alert — только вместе с текстом.
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
function tokenPair(): TokenPair {
  return {
    accessToken: 'access-2',
    refreshToken: 'refresh-2',
    tokenType: 'Bearer',
    expiresInSec: 300,
  }
}

function renderModal() {
  const onSuccess = vi.fn()
  const onCancel = vi.fn()
  render(
    <ToastProvider>
      <PasswordChangeModal
        username={ACCOUNT.username}
        email={ACCOUNT.email}
        onSuccess={onSuccess}
        onCancel={onCancel}
      />
    </ToastProvider>,
  )
  return { onSuccess, onCancel }
}

function fillPasswordForm(current: string, next: string, confirm: string) {
  fireEvent.change(screen.getByLabelText('Текущий пароль'), { target: { value: current } })
  fireEvent.change(screen.getByLabelText('Новый пароль'), { target: { value: next } })
  fireEvent.change(screen.getByLabelText('Повторите новый пароль'), {
    target: { value: confirm },
  })
  fireEvent.click(screen.getByRole('button', { name: 'Сменить пароль' }))
}

/** Тост-слот пуст: ошибка модали не рождает тост (FR-025). */
function expectNoToast() {
  expect(screen.queryByRole('status')?.textContent).toBe('')
}

afterEach(() => {
  cleanup()
  vi.resetAllMocks()
})

describe('PasswordChangeModal', () => {
  it('renders current/new/confirm fields with password-manager autocomplete hints', () => {
    renderModal()

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

  it('footer .modal-btns: «Отмена» (m-btn) + «Сменить пароль» (m-btn primary)', () => {
    renderModal()

    const footer = document.querySelector('.modal-btns') as HTMLElement
    expect(footer).not.toBeNull()
    expect(within(footer).getByRole('button', { name: 'Отмена' })).toHaveClass('m-btn')
    const submit = within(footer).getByRole('button', { name: 'Сменить пароль' })
    expect(submit).toHaveClass('m-btn')
    expect(submit).toHaveClass('primary')
  })

  it('error line .modal-err is persistent (min-height holds the layout); role=alert only with text', () => {
    renderModal()

    const errorLine = document.querySelector('.modal-err') as HTMLElement
    expect(errorLine).not.toBeNull()
    expect(errorLine).toHaveTextContent('')
    expect(errorLine).not.toHaveAttribute('role')
  })

  it.each([
    [
      'пароль из одних пробелов',
      '        ',
      '        ',
      'Пароль не должен быть пустым или состоять только из пробелов',
    ],
    ['длину меньше 8 символов', 'Sh0rt!1', 'Sh0rt!1', 'Пароль должен быть не короче 8 символов'],
    [
      'длину больше 128 символов',
      'a1B!'.repeat(32) + 'z',
      'a1B!'.repeat(32) + 'z',
      'Пароль должен быть не длиннее 128 символов',
    ],
    ['несовпадающее подтверждение', 'Str0ng!2', 'Str0ng!3', 'Пароли не совпадают'],
    [
      'совпадение с username',
      'hargrove',
      'hargrove',
      'Пароль не должен совпадать с именем пользователя',
    ],
    [
      'совпадение с email без учёта регистра',
      'H.HARGROVE@AETHERGRAM.IO',
      'H.HARGROVE@AETHERGRAM.IO',
      'Пароль не должен совпадать с email',
    ],
  ])(
    'client validation blocks submit for %s: no request, russian inline alert',
    (_r, next, c, message) => {
      mockChangePassword.mockResolvedValueOnce(tokenPair())
      const { onSuccess } = renderModal()

      fillPasswordForm(CURRENT_PASSWORD, next, c)

      expect(screen.getByRole('alert')).toHaveTextContent(message)
      expect(mockChangePassword).not.toHaveBeenCalled()
      expect(mockSetTokenPair).not.toHaveBeenCalled()
      expectNoToast()
      expect(onSuccess).not.toHaveBeenCalled()
    },
  )

  it('submits exactly {currentPassword, newPassword}, applies the new pair, one toast, closes via onSuccess', async () => {
    mockChangePassword.mockResolvedValueOnce(tokenPair())
    const { onSuccess } = renderModal()

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
    await waitFor(() => expect(onSuccess).toHaveBeenCalledTimes(1))
  })

  it('400 currentPassword:invalid → «Текущий пароль неверен», fields kept, no toast, no close', async () => {
    mockChangePassword.mockRejectedValueOnce({
      title: 'Bad Request',
      status: 400,
      errors: { currentPassword: ['invalid'] },
    } satisfies Problem)
    const { onSuccess } = renderModal()

    fillPasswordForm(CURRENT_PASSWORD, NEW_PASSWORD, NEW_PASSWORD)

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Текущий пароль неверен')
    expectNoToast()
    expect(mockSetTokenPair).not.toHaveBeenCalled()
    expect(onSuccess).not.toHaveBeenCalled()
    // значения сохранены для повтора (паттерн .modal-err)
    expect(screen.getByLabelText('Текущий пароль')).toHaveValue(CURRENT_PASSWORD)
    expect(screen.getByLabelText('Новый пароль')).toHaveValue(NEW_PASSWORD)
  })

  it.each([
    ['too_common', 'password123', 'Этот пароль слишком распространён — выберите другой'],
    ['too_short', 'Sh0rt!1', 'Пароль должен быть не короче 8 символов'],
    ['too_long', 'a1B!'.repeat(32) + 'z', 'Пароль должен быть не длиннее 128 символов'],
    ['blank', '        ', 'Пароль не должен быть пустым или состоять только из пробелов'],
    ['same_as_username', 'hargrove', 'Пароль не должен совпадать с именем пользователя'],
    ['same_as_email', 'H.HARGROVE@AETHERGRAM.IO', 'Пароль не должен совпадать с email'],
  ])('400 newPassword:%s → russian message', async (code, next, message) => {
    // too_short/too_long/blank/same_as_* отсекаются клиентом ДО запроса —
    // до сервера эти коды доходят при расхождении версий; для too_common
    // это штатный путь (топ-лист слабых — серверная проверка, research §8)
    mockChangePassword.mockRejectedValueOnce({
      title: 'Bad Request',
      status: 400,
      errors: { newPassword: [code] },
    } satisfies Problem)
    const { onSuccess } = renderModal()

    fillPasswordForm(CURRENT_PASSWORD, next, next)

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(message)
    expectNoToast()
    expect(mockSetTokenPair).not.toHaveBeenCalled()
    expect(onSuccess).not.toHaveBeenCalled()
  })

  it.each([
    [60, 'через 1 минуту'],
    [65, 'через 2 минуты'],
    [300, 'через 5 минут'],
    [3600, 'через 1 час'],
    [7200, 'через 2 часа'],
    [18000, 'через 5 часов'],
  ])('429 + retryAfterSec=%i → «Слишком много попыток — … %s»', async (retryAfterSec, tail) => {
    mockChangePassword.mockRejectedValueOnce({
      title: 'Too Many Requests',
      status: 429,
      retryAfterSec,
    } satisfies Problem & { retryAfterSec: number })
    renderModal()

    fillPasswordForm(CURRENT_PASSWORD, NEW_PASSWORD, NEW_PASSWORD)

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(`Слишком много попыток — попробуйте ${tail}`)
    expectNoToast()
  })

  it('401 → «Сессия истекла — войдите заново»', async () => {
    mockChangePassword.mockRejectedValueOnce({
      title: 'Unauthorized',
      status: 401,
    } satisfies Problem)
    renderModal()

    fillPasswordForm(CURRENT_PASSWORD, NEW_PASSWORD, NEW_PASSWORD)

    expect(await screen.findByText('Сессия истекла — войдите заново')).toBeVisible()
    expectNoToast()
  })

  it.each([
    [
      'сетевой сбой (не problem-объект)',
      new TypeError('NetworkError when attempting to fetch resource.'),
    ],
    ['неизвестная проблема 500', { title: 'Internal Server Error', status: 500 } satisfies Problem],
  ])('%s → русский fallback', async (_r, cause) => {
    mockChangePassword.mockRejectedValueOnce(cause)
    renderModal()

    fillPasswordForm(CURRENT_PASSWORD, NEW_PASSWORD, NEW_PASSWORD)

    expect(
      await screen.findByText(
        'Не удалось изменить пароль. Проверьте соединение и попробуйте ещё раз',
      ),
    ).toBeVisible()
    expectNoToast()
  })

  it('«Отмена» закрывает без запроса', () => {
    const { onCancel } = renderModal()

    fireEvent.click(screen.getByRole('button', { name: 'Отмена' }))

    expect(onCancel).toHaveBeenCalledTimes(1)
    expect(mockChangePassword).not.toHaveBeenCalled()
    expectNoToast()
  })

  it('все кнопки/поля disabled на время отправки', async () => {
    let resolveChange!: (pair: TokenPair) => void
    mockChangePassword.mockReturnValueOnce(
      new Promise<TokenPair>((resolve) => {
        resolveChange = resolve
      }),
    )
    const { onSuccess } = renderModal()

    fillPasswordForm(CURRENT_PASSWORD, NEW_PASSWORD, NEW_PASSWORD)

    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Сменить пароль' })).toBeDisabled()
    })
    expect(screen.getByRole('button', { name: 'Отмена' })).toBeDisabled()
    expect(screen.getByLabelText('Текущий пароль')).toBeDisabled()
    expect(screen.getByLabelText('Новый пароль')).toBeDisabled()
    expect(screen.getByLabelText('Повторите новый пароль')).toBeDisabled()

    resolveChange(tokenPair())
    await waitFor(() => expect(onSuccess).toHaveBeenCalledTimes(1))
    expect(screen.getByRole('button', { name: 'Отмена' })).toBeEnabled()
  })
})
