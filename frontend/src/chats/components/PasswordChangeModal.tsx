/**
 * Модальная форма «Смена пароля» «Aethergram» (008b US2, T046; FR-004/
 * FR-005, contracts §1, research §8): житель ЕДИНОЙ модальной оболочки
 * ModalShell (formId 'password-change', заголовок «Смена пароля» —
 * MessengerPage), открывается кнопкой «Смена пароля» из «Мой профиль»
 * — вынос инлайн-формы T022/T023 в отдельную модаль по образцу
 * CreateGroupDialog (локальный state полей, submit → валидация → POST).
 *
 * - Поля current/new/confirm с подсказками менеджеров паролей:
 *   autoComplete current-password / new-password / new-password
 *   (contract §4.3); maxlength 128 — зеркало серверной политики 002.
 *
 * - Клиентская валидация validateNewPassword (T020, русские строки)
 *   ДО отправки: длина 8–128, не пустой/не из пробелов, new = confirm,
 *   ≠ username/email (case-insensitive + trim) — запроса нет вовсе;
 *   топ-лист слабых паролей — только сервер (research §8): 400
 *   newPassword: too_common рендерится понятным русским сообщением.
 *
 * - Маппинг problem → русские сообщения (T046 → T047: полевые коды
 *   newPassword и 429 — ОБЩИЙ модуль auth/lib/problemRu;
 *   локально — 401 и `currentPassword: invalid`; глобальный
 *   problemMessage НЕ трогается, прочие экраны не меняются): 400
 *   `currentPassword: invalid` → «Текущий пароль
 *   неверен»; 400 `newPassword:` blank/too_short/too_long/too_common/
 *   same_as_username/same_as_email; 429 + retryAfterSec → «Слишком
 *   много попыток — попробуйте через N» (склонение секунд/минут/часов);
 *   401 → «Сессия истекла — войдите заново»; сетевой сбой/неизвестная
 *   ошибка → единый русский fallback.
 *
 * - Успех → POST /auth/password-change (T021) → setTokenPair — единая
 *   точка ротации, пользователь остаётся вошедшим (FR-008; прочие
 *   сессии отозваны сервером) → ровно один toast «Пароль изменён»
 *   (FR-025) + onSuccess-колбэк владельцу оболочки — закрытие; очистка
 *   полей не нужна — модаль закрывается.
 *
 * - «Отмена»/Esc/фон — onCancel без запроса; возврат в «Мой профиль» —
 *   вручную, не автоматически (один житель оболочки).
 *
 * - Строка .modal-err ПОСТОЯННАЯ (паттерн CreateGroupDialog: min-height
 *   держит компоновку), role=alert появляется вместе с текстом; все
 *   кнопки/поля disabled на время отправки.
 */
import { useState, type SubmitEvent } from 'react'
import { changePassword } from '../../api/auth'
import { validateNewPassword } from '../../auth/lib/passwordPolicy'
import { isFieldErrors, passwordProblemMessageRu } from '../../auth/lib/problemRu'
import { setTokenPair } from '../../auth/session'
import { useToast } from '../../ui/Toast'
import './profile-modal.css'

export interface PasswordChangeModalProps {
  /** Контекст клиентской проверки: новый пароль ≠ username/email (T020). */
  readonly username: string
  readonly email: string
  /** Успех — колбэк владельцу оболочки: закрытие (модаль поглощает форму). */
  readonly onSuccess?: () => void
  /** «Отмена» — закрытие без запроса. */
  readonly onCancel?: () => void
}

/** Сетевой сбой/неизвестная проблема — единый русский fallback (T046). */
const PROBLEM_FALLBACK = 'Не удалось изменить пароль. Проверьте соединение и попробуйте ещё раз'

/**
 * ЛОКАЛЬНЫЙ маппинг problem+json смены пароля → русские сообщения
 * (T046 → T047: полевые коды newPassword и 429 — общий модуль
 * problemRu (до T048 — passwordProblemRu); ЗДЕСЬ остаются специфичные
 * ветки флоу — 401
 * «Сессия истекла» (Bearer-граница) и 400 currentPassword: invalid).
 */
function passwordChangeProblemMessage(cause: unknown): string {
  if (typeof cause === 'object' && cause !== null) {
    const problem = cause as { status?: unknown; errors?: unknown }
    if (problem.status === 401) {
      // Граница Bearer: и access, и refresh уже не спасают — нужен вход.
      return 'Сессия истекла — войдите заново'
    }
    if (
      problem.status === 400 &&
      isFieldErrors(problem.errors) &&
      problem.errors.currentPassword?.includes('invalid')
    ) {
      return 'Текущий пароль неверен'
    }
  }
  return passwordProblemMessageRu(cause, PROBLEM_FALLBACK)
}

export function PasswordChangeModal({
  username,
  email,
  onSuccess,
  onCancel,
}: PasswordChangeModalProps) {
  const showToast = useToast()
  const [currentPassword, setCurrentPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)

  /** Submit: клиентская валидация → POST /auth/password-change (FR-008). */
  const handleSubmit = (event: SubmitEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (saving) {
      return
    }
    // Клиентская проверка до отправки — запроса нет вовсе (FR-005);
    // confirmPassword — только клиентская проверка, в API поля нет.
    const invalid = validateNewPassword(newPassword, { username, email, confirm: confirmPassword })
    if (invalid !== null) {
      setError(invalid)
      return
    }
    setSaving(true)
    setError(null)
    void changePassword({ currentPassword, newPassword })
      .then((pair) => {
        // Единая точка ротации: пользователь остаётся вошедшим (FR-008).
        setTokenPair(pair)
        showToast('Пароль изменён')
        onSuccess?.()
      })
      .catch((cause: unknown) => {
        // Значения сохранены для повтора; тоста нет (паттерн .modal-err).
        setError(passwordChangeProblemMessage(cause))
      })
      .finally(() => {
        setSaving(false)
      })
  }

  return (
    <form className="profile-form" onSubmit={handleSubmit}>
      <label htmlFor="password-change-current">Текущий пароль</label>
      <input
        id="password-change-current"
        className="profile-pass"
        type="password"
        autoComplete="current-password"
        maxLength={128}
        value={currentPassword}
        disabled={saving}
        onChange={(event) => {
          setCurrentPassword(event.target.value)
        }}
      />
      <label htmlFor="password-change-new">Новый пароль</label>
      <input
        id="password-change-new"
        className="profile-pass"
        type="password"
        autoComplete="new-password"
        maxLength={128}
        value={newPassword}
        disabled={saving}
        onChange={(event) => {
          setNewPassword(event.target.value)
        }}
      />
      <label htmlFor="password-change-confirm">Повторите новый пароль</label>
      <input
        id="password-change-confirm"
        className="profile-pass"
        type="password"
        autoComplete="new-password"
        maxLength={128}
        value={confirmPassword}
        disabled={saving}
        onChange={(event) => {
          setConfirmPassword(event.target.value)
        }}
      />
      <div className="modal-err" role={error !== null ? 'alert' : undefined}>
        {error}
      </div>
      <div className="modal-btns">
        <button type="button" className="m-btn" onClick={onCancel} disabled={saving}>
          Отмена
        </button>
        <button type="submit" className="m-btn primary" disabled={saving}>
          Сменить пароль
        </button>
      </div>
    </form>
  )
}
