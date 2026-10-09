/**
 * Модальная форма «Мой профиль» «Aethergram» (feature 008, US2, T033;
 * FR-015, ui-behavior §3, research §D; 008a US1, T021; ui-behavior §1.1):
 * житель ЕДИНОЙ модальной оболочки ModalShell (T034, MessengerPage) —
 * проекция #profileForm прототипа specs/008-chat-window-styling/design/
 * chats.html С полем «Имя» (#profileInput прототипа, 008a FR-001).
 *
 * - Поле «Имя» (input над строкой инкогнито): placeholder = username,
 *   maxlength 64; предзаполнено профильным displayName из №10
 *   (отсутствует → пусто). Сохранение №39 `PUT /users/me/profile`
 *   (updateProfile): полностью очищенное поле — явный `null` (сброс к
 *   username); непустая строка только из пробелов — клиентская ошибка
 *   валидации «Укажите имя без пробелов в начале и конце» БЕЗ запроса
 *   (зеркалит серверную 400 invalid_display_name); иные значения сервер
 *   сохраняет после trim (1–64), при ошибке прежнее значение сохраняется.
 *
 * - username/email — ТОЛЬКО ДЛЯ ЧТЕНИЯ: строки .modal-ro (`<span>` +
 *   `<b>`) из `GET /users/me` (getCurrentUser), email-фолбэк «—»
 *   (openProfile прототипа).
 *
 * - Переключатель «Режим инкогнито» (.tgl-row прототипа): семантика и
 *   API 007 — №38 GET /users/me/presence/settings на монтировании
 *   отражает персистентный режим (переживает перелогин), подпись
 *   «Всем участникам вы отображаетесь как offline» дословно из
 *   прототипа.
 *
 * - Сохранить → №39 PUT → №38 PUT → ровно один тост (FR-025) и закрытие
 *   (submit profileForm → closeModal + toast прототипа): тост
 *   «Профиль обновлён — {displayName || username}» (resolveDisplayName,
 *   ответ №39) + « · инкогнито: вы offline для всех» при включённом
 *   режиме; собственный displayName применяется немедленно. Сбой PUT —
 *   ошибка .modal-err, чекбокс возвращается к серверному значению
 *   (поведение 007 PresenceSettingsPage, FR-034), тоста и закрытия нет.
 *
 * - Загрузка/сбой загрузки — единый стиль (FR-032): «Загрузка…» /
 *   .modal-err + «Повторить» с рефетчем обоих источников (паттерн
 *   №20-сбоя ContactsModal).
 *
 * Встраивание в ModalShell MessengerPage (заголовок «Мой профиль»,
 * закрытие Esc/фоном) — T034; Отмена здесь — только колбэк onClose.
 */
import { useCallback, useEffect, useState, type SubmitEvent } from 'react'
import { getCurrentUser } from '../../api/auth'
import { updateProfile } from '../../api/chats'
import { problemMessage } from '../../auth/problem'
import { fetchPresenceSettings, updatePresenceSettings } from '../../presence/presenceApi'
import { useToast } from '../../ui/Toast'
import { resolveDisplayName } from '../../ui/names'
import './profile-modal.css'

export interface ProfileModalProps {
  /** Отмена/успешное сохранение — закрытие оболочки (владелец T034). */
  readonly onClose?: () => void
}

type LoadState = 'loading' | 'ready' | 'error'

export function ProfileModal({ onClose }: ProfileModalProps) {
  const showToast = useToast()
  const [status, setStatus] = useState<LoadState>('loading')
  const [username, setUsername] = useState('')
  const [email, setEmail] = useState('')
  const [nameInput, setNameInput] = useState('')
  const [incognito, setIncognito] = useState(false)
  /** Последнее серверное значение №38 — цель отката при сбое PUT (007). */
  const [persisted, setPersisted] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)

  /** GET /users/me + №38 GET: единственный путь загрузки формы (+ «Повторить»). */
  const loadProfile = useCallback(() => {
    setStatus('loading')
    setError(null)
    Promise.all([getCurrentUser(), fetchPresenceSettings()])
      .then(([me, settings]) => {
        setUsername(me.username)
        setEmail(me.email)
        setNameInput(me.displayName ?? '')
        setIncognito(settings.incognito)
        setPersisted(settings.incognito)
        setStatus('ready')
      })
      .catch((cause: unknown) => {
        setError(problemMessage(cause))
        setStatus('error')
      })
  }, [])
  useEffect(() => {
    loadProfile()
  }, [loadProfile])

  /** Submit #profileForm прототипа: №39 PUT (имя) → №38 PUT → тост → закрытие. */
  const handleSubmit = (event: SubmitEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (saving) {
      return
    }
    // Непустая строка только из пробелов — не сброс, а ошибка валидации
    // (зеркаль серверной 400 invalid_display_name) — запроса нет вовсе.
    if (nameInput.length > 0 && nameInput.trim().length === 0) {
      setError('Укажите имя без пробелов в начале и конце')
      return
    }
    setSaving(true)
    setError(null)
    // Полностью очищенное поле — явный null (сброс к username), не «как есть».
    void updateProfile({ displayName: nameInput.trim() === '' ? null : nameInput })
      .then((me) => updatePresenceSettings(incognito).then((settings) => ({ me, settings })))
      .then(({ me, settings }) => {
        setPersisted(settings.incognito)
        setIncognito(settings.incognito)
        showToast(
          `Профиль обновлён — ${resolveDisplayName(undefined, me.displayName, username)}` +
            (settings.incognito ? ' · инкогнито: вы offline для всех' : ''),
        )
        onClose?.()
      })
      .catch((cause: unknown) => {
        setIncognito(persisted)
        setError(problemMessage(cause))
      })
      .finally(() => {
        setSaving(false)
      })
  }

  if (status !== 'ready') {
    return (
      <div className="profile-form">
        {status === 'loading' && <output className="pick-empty">Загрузка…</output>}
        {status === 'error' && (
          <>
            <div className="modal-err" role="alert">
              {error}
            </div>
            <div className="modal-btns">
              <button type="button" className="m-btn" onClick={loadProfile}>
                Повторить
              </button>
            </div>
          </>
        )}
      </div>
    )
  }

  return (
    <form className="profile-form" onSubmit={handleSubmit}>
      <label htmlFor="profile-name">Имя</label>
      <input
        id="profile-name"
        className="profile-name"
        placeholder={username}
        maxLength={64}
        autoComplete="off"
        value={nameInput}
        disabled={saving}
        onChange={(event) => {
          setNameInput(event.target.value)
        }}
      />
      <label className="tgl-row">
        <input
          type="checkbox"
          checked={incognito}
          disabled={saving}
          onChange={(event) => {
            setIncognito(event.target.checked)
          }}
        />
        <span className="tgl-txt">
          {'Режим инкогнито '}
          <small>Всем участникам вы отображаетесь как offline</small>
        </span>
      </label>
      <div className="modal-ro">
        <span>username</span>
        <b>{username}</b>
      </div>
      <div className="modal-ro">
        <span>email</span>
        <b>{email || '—'}</b>
      </div>
      {error !== null && (
        <div className="modal-err" role="alert">
          {error}
        </div>
      )}
      <div className="modal-btns">
        <button type="button" className="m-btn" onClick={onClose}>
          Отмена
        </button>
        <button type="submit" className="m-btn primary" disabled={saving}>
          Сохранить
        </button>
      </div>
    </form>
  )
}
