/**
 * Модальная форма «Мой профиль» «Aethergram» (feature 008, US2, T033;
 * FR-015, ui-behavior §3, research §D): житель ЕДИНОЙ модальной
 * оболочки ModalShell (T034, MessengerPage) — проекция #profileForm
 * прототипа specs/008-chat-window-styling/design/chats.html БЕЗ
 * редактирования имени (008a — вне объёма фичи, FR-015): имя-поле
 * прототипа (#profileInput) не переносится.
 *
 * - username/email — ТОЛЬКО ДЛЯ ЧТЕНИЯ: строки .modal-ro (`<span>` +
 *   `<b>`) из `GET /users/me` (getCurrentUser), email-фолбэк «—»
 *   (openProfile прототипа); редактируемых полей в форме нет вовсе.
 *
 * - Переключатель «Режим инкогнито» (.tgl-row прототипа): семантика и
 *   API 007 — №38 GET /users/me/presence/settings на монтировании
 *   отражает персистентный режим (переживает перелогин), подпись
 *   «Всем участникам вы отображаетесь как offline» дословно из
 *   прототипа.
 *
 * - Сохранить → №38 PUT → ровно один тост (FR-025) и закрытие
 *   (submit profileForm → closeModal + toast прототипа): тост
 *   «Профиль обновлён — {username}» + « · инкогнито: вы offline для
 *   всех» при включённом режиме (имя-часть прототипа вырождается в
 *   username — имени в 008 нет). Сбой PUT — ошибка .modal-err,
 *   чекбокс возвращается к серверному значению (поведение 007
 *   PresenceSettingsPage, FR-034), тоста и закрытия нет.
 *
 * - Загрузка/сбой загрузки — единый стиль (FR-032): «Загрузка…» /
 *   .modal-err + «Повторить» с рефетчем обоих источников (паттерн
 *   №20-сбоя ContactsModal).
 *
 * Встраивание в ModalShell MessengerPage (заголовок «Мой профиль»,
 * закрытие Esc/фоном) — T034; Отмена здесь — только колбэк onClose.
 */
import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { getCurrentUser } from '../../api/auth'
import { problemMessage } from '../../auth/problem'
import { fetchPresenceSettings, updatePresenceSettings } from '../../presence/presenceApi'
import { useToast } from '../../ui/Toast'
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

  /** Submit #profileForm прототипа: №38 PUT → тост → закрытие; сбой — откат чекбокса. */
  const handleSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (saving) {
      return
    }
    setSaving(true)
    setError(null)
    void updatePresenceSettings(incognito)
      .then((settings) => {
        setPersisted(settings.incognito)
        setIncognito(settings.incognito)
        showToast(
          `Профиль обновлён — ${username}` +
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
        {status === 'loading' && (
          <div className="pick-empty" role="status">
            Загрузка…
          </div>
        )}
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
          Режим инкогнито
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
