/**
 * Форма смены пароля «Мой профиль» «Aethergram» (008b US2, T022; FR-004/
 * FR-005, contracts §1, research §8): отдельная <form> внутри модали
 * профиля (T023) — submit не затрагивает «Сохранить» профиля.
 *
 * - Поля current/new/confirm с подсказками менеджеров паролей:
 *   autoComplete current-password / new-password / new-password
 *   (contract §4.3); maxlength 128 — зеркало серверной политики 002.
 *
 * - Клиентская валидация validateNewPassword (T020) ДО отправки:
 *   длина 8–128, не пустой/не из пробелов, new = confirm,
 *   ≠ username/email (case-insensitive + trim) — ошибка .modal-err
 *   role="alert" без запроса вовсе; топ-лист слабых паролей — только
 *   сервер (research §8): 400 newPassword: too_common рендерится
 *   понятным сообщением через problemMessage.
 *
 * - Успех → POST /auth/password-change (T021) → setTokenPair — единая
 *   точка ротации, пользователь остаётся вошедшим (FR-008) → ровно
 *   один toast «Пароль изменён» (FR-025) + очистка полей.
 *
 * - Сбой — inline .modal-err role="alert" через problemMessage без
 *   toast; значения полей сохранены для повтора (паттерн ProfileModal).
 */
import { useState, type SubmitEvent } from 'react'
import { changePassword } from '../../api/auth'
import { validateNewPassword } from '../../auth/lib/passwordPolicy'
import { problemMessage } from '../../auth/problem'
import { setTokenPair } from '../../auth/session'
import { useToast } from '../../ui/Toast'
import './profile-modal.css'

export interface PasswordChangeFormProps {
  /** Контекст клиентской проверки: новый пароль ≠ username/email (T020). */
  readonly username: string
  readonly email: string
}

export function PasswordChangeForm({ username, email }: PasswordChangeFormProps) {
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
        setCurrentPassword('')
        setNewPassword('')
        setConfirmPassword('')
      })
      .catch((cause: unknown) => {
        // Значения сохранены для повтора; тоста нет (паттерн .modal-err).
        setError(problemMessage(cause))
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
      {error !== null && (
        <div className="modal-err" role="alert">
          {error}
        </div>
      )}
      <div className="modal-btns">
        <button type="submit" className="m-btn primary" disabled={saving}>
          Сменить пароль
        </button>
      </div>
    </form>
  )
}
