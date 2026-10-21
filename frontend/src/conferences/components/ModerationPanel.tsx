/**
 * Панель модерации конференции (009, US3, T042; FR-008, №48–№51):
 * серверные действия модератора — мьют микрофона/камеры/экрана,
 * «право слова», назначение/снятие модератора, кик и завершение
 * конференции. Рендерится в слоте children ConferenceRoom (внутри
 * RoomContext) рядом с ParticipantsPanel (T041): действия адресуются
 * по identity строк LiveKit.
 *
 * Модерация ТОЛЬКО серверная (FR-008): клиент не держит roomAdmin —
 * каждый действ является REST-вызовом контрольной плоскости, сервер
 * применяет его к медиа-плоскости (UpdateParticipant grants/metadata +
 * MutePublishedTrack, RemoveParticipant, DeleteRoom — livekit-integration
 * §4) немедленно ≤ 2 c (SC-005). Поэтому панель НЕ хранит ограничений
 * локально: состояние кнопок — проекция фактических grants LiveKit
 * (restrictionMarks T041, источник истины — permission-события), а
 * №49 отправляет ПОЛНОЕ SET-состояние (контракт: частичное обновление
 * невозможно) — текущее из grants с переключённым полем. Роль цели —
 * из metadata (broadcast UpdateParticipant, Q9=A).
 *
 * Видимость панели — роль ЛОКАЛЬНОГО подключения из его metadata
 * (не №46-снимок pre-join): назначение/снятие модератора №50 приходит
 * broadcast'ом metadata и панель появляется/исчезает живьём. Дефолт
 * битого/отсутствующего metadata — «участник»: панель ошибочно не
 * показывается (fail-closed). Модераторы равноправны (Q21=A): действия
 * доступны для ЛЮБОЙ строки, включая других модераторов, создателя и
 * себя — правовой гейт единый на сервере (403 moderator_required).
 *
 * Деструктивные действия — кик (немедленный разрыв подключения),
 * завершение (все отключаются) и отзыв гостевой ссылки №53 (вошедшие
 * по ней гости отключаются немедленно, Q13=B) — требуют явного
 * подтверждения (role=alertdialog, подтверждение отдельным нажатием);
 * ограничения и смена роли обратимы — выполняются сразу. Отказ —
 * нейтральная строка по коду Problem (moderator_required/
 * participant_not_found/conference_ended), без раскрытия деталей;
 * live-строки обновляются сами событиями LiveKit — ручной отккат
 * состояния не нужен.
 *
 * Блок «Гостевая ссылка» (T081, US4, №52/№53): по монтажу свёрнут в
 * кнопку «Пригласить гостя» — GET статуса ссылки в контракте нет,
 * наблюдать активную без генерации невозможно (FR-009 минимум
 * раскрытия); после размонтирования панели — снова свёрнуто (кэш не
 * вводится). Успешный №52 — readonly-поле inviteUrl + «Копировать»
 * (navigator.clipboard; сбой — молча, ручное выделение остаётся
 * страховкой) + срок из expiresAt локальным временем + предупреждение
 * Q17=A/Q13=B; повторная генерация — та же кнопка №52 (сервер сам
 * отзывает предыдущую). Отзыв №53 с подтверждением; `404
 * guest_invite_not_found` — не ошибка: молчаливый коллапс блока
 * (идемпотентность — ссылку мог отозвать другой модератор, Q21=A).
 *
 * Доступность — стандарты 008: section aria-labelledby, группы
 * действий row — fieldset (implicit role=group), фокус с клавиатуры
 * (conferences.css), «в полёте» все кнопки недоступны (без гонок
 * повторных вызовов).
 */
import { useLocalParticipant, useParticipants } from '@livekit/components-react'
import type { LocalParticipant, RemoteParticipant } from 'livekit-client'
import { useId, useState } from 'react'
import {
  createGuestInvite,
  endConference,
  guestInviteErrorCode,
  kickParticipant,
  moderationErrorCode,
  revokeGuestInvite,
  updateParticipantRestrictions,
  updateParticipantRole,
} from '../api/conferences'
import type {
  GuestInviteErrorCode,
  GuestInviteView,
  ModerationErrorCode,
  UpdateParticipantRestrictionsRequest,
} from '../api/conferences'
import { inviteExpiresLabel } from '../invite'
import {
  orderParticipants,
  parseParticipantMarks,
  participantLabel,
  restrictionMarks,
} from './ParticipantsPanel'
import type { ConferenceParticipantRole } from './ParticipantsPanel'
import '../conferences.css'

export interface ModerationPanelProps {
  /** Адрес REST-действий №48–№53 (идентификатор конференции). */
  readonly conferenceId: string
}

/** Поле ограничения №49, переключаемое строкой панели. */
type RestrictionField = keyof UpdateParticipantRestrictionsRequest

/** Действие в полёте (все кнопки панели недоступны до завершения). */
type ModerationAction =
  | { readonly kind: 'restrictions'; readonly identity: string; readonly field: RestrictionField }
  | { readonly kind: 'role'; readonly identity: string }
  | { readonly kind: 'kick'; readonly identity: string }
  | { readonly kind: 'invite' }
  | { readonly kind: 'revoke_invite' }
  | { readonly kind: 'end' }

/** Ожидаемое подтверждение деструктивного действия. */
type ModerationConfirmation =
  | { readonly kind: 'kick'; readonly identity: string; readonly label: string }
  | { readonly kind: 'revoke_invite' }
  | { readonly kind: 'end' }

/** Нейтральное сообщение об отказе действия по коду Problem (без деталей). */
function moderationNotice(code: ModerationErrorCode | GuestInviteErrorCode | null): string {
  switch (code) {
    case 'moderator_required':
      return 'Недостаточно прав'
    case 'participant_not_found':
      return 'Участник уже не в конференции'
    case 'conference_ended':
      return 'Конференция завершена'
    case 'conference_not_found':
      return 'Конференция не найдена'
    default:
      return 'Не удалось выполнить действие'
  }
}

/** Подпись подтверждения деструктивного действия. */
function confirmationText(confirmation: ModerationConfirmation): string {
  if (confirmation.kind === 'end') {
    return 'Завершить конференцию для всех? Все участники отключатся немедленно.'
  }
  if (confirmation.kind === 'revoke_invite') {
    return 'Отозвать гостевую ссылку? Все вошедшие по ней гости отключатся немедленно, новые входы блокируются.'
  }
  return `Исключить ${confirmation.label} из конференции? Участник отключится немедленно и сможет вернуться по действующей ссылке.`
}

interface ModerationRowProps {
  readonly participant: LocalParticipant | RemoteParticipant
  /** Все кнопки недоступны, пока летит любое действие панели. */
  readonly disabled: boolean
  readonly onRestriction: (identity: string, field: RestrictionField) => void
  readonly onRole: (identity: string) => void
  readonly onKick: (identity: string, label: string) => void
}

/**
 * Строка модерации: цель (имя/«вы») и действия №49–№51 по её grants/
 * metadata. Состояния кнопок — проекция LiveKit (restrictionMarks/
 * parseParticipantMarks T041); «право слова» поглощает точечные
 * источники (Q4=A), поэтому при нём точечные переключатели отражают
 * свои доменные поля SET-состояния.
 */
function ModerationRow({
  participant,
  disabled,
  onRestriction,
  onRole,
  onKick,
}: ModerationRowProps) {
  const marks = parseParticipantMarks(participant.metadata)
  const restrictions = restrictionMarks(participant.permissions)
  const label = participantLabel(participant.name, participant.identity)
  const rowLabel = participant.isLocal ? `${label} (вы)` : label

  const restrictionButtons: ReadonlyArray<{
    readonly field: RestrictionField
    readonly restrict: boolean
    readonly restrictText: string
    readonly allowText: string
  }> = [
    {
      field: 'micRestricted',
      restrict: restrictions.micRestricted,
      restrictText: 'Включить микрофон',
      allowText: 'Отключить микрофон',
    },
    {
      field: 'cameraRestricted',
      restrict: restrictions.cameraRestricted,
      restrictText: 'Включить камеру',
      allowText: 'Отключить камеру',
    },
    {
      field: 'screenRestricted',
      restrict: restrictions.screenRestricted,
      restrictText: 'Включить экран',
      allowText: 'Отключить экран',
    },
    {
      field: 'speechRestricted',
      restrict: restrictions.speechRestricted,
      restrictText: 'Снять право слова',
      allowText: 'Право слова',
    },
  ]

  return (
    <li className="conference-moderation-item" data-participant-identity={participant.identity}>
      <span className="conference-moderation-name">{rowLabel}</span>
      <fieldset className="conference-moderation-actions" aria-label={rowLabel}>
        {restrictionButtons.map(({ field, restrict, restrictText, allowText }) => (
          <button
            key={field}
            type="button"
            className={`conference-moderation-button${
              restrict ? ' conference-moderation-button-restricted' : ''
            }`}
            disabled={disabled}
            onClick={() => {
              onRestriction(participant.identity, field)
            }}
          >
            {restrict ? restrictText : allowText}
          </button>
        ))}
        {/* №50: равноправие модераторов (Q21=A) — смена роли доступна любой строке */}
        <button
          type="button"
          className="conference-moderation-button"
          disabled={disabled}
          onClick={() => {
            onRole(participant.identity)
          }}
        >
          {marks.role === 'moderator' ? 'Снять модератора' : 'Назначить модератором'}
        </button>
        <button
          type="button"
          className="conference-moderation-button conference-moderation-button-danger"
          disabled={disabled}
          onClick={() => {
            onKick(participant.identity, rowLabel)
          }}
        >
          Исключить
        </button>
      </fieldset>
    </li>
  )
}

/**
 * Панель модерации: действия №49–№51 по строкам участников и №48
 * «Завершить конференцию». Не-модератору не рендерится (роль локального
 * подключения — metadata LiveKit, live-обновление broadcast'ом №50).
 */
export default function ModerationPanel({ conferenceId }: ModerationPanelProps) {
  const titleId = useId()
  const dialogTitleId = useId()
  const { localParticipant } = useLocalParticipant()
  const participants = useParticipants()
  /** Действие в полёте: одна операция REST-модерации единовременно. */
  const [pending, setPending] = useState<ModerationAction | null>(null)
  /** Ожидаемое подтверждение деструктивного действия (кик/отзыв/завершение). */
  const [confirmation, setConfirmation] = useState<ModerationConfirmation | null>(null)
  /** Нейтральная строка последнего отказа (сбрасывается следующей попыткой). */
  const [notice, setNotice] = useState<string | null>(null)
  /** Действующая гостевая ссылка в руках модератора (T081; null — блок свёрнут). */
  const [guestInvite, setGuestInvite] = useState<GuestInviteView | null>(null)
  /** Статус «Скопировано» после успешного clipboard.writeText. */
  const [copied, setCopied] = useState(false)

  const localMarks = parseParticipantMarks(localParticipant.metadata)
  if (localMarks.role !== 'moderator') {
    // Роль сняли №50 (broadcast metadata) — панель исчезает сама (Q9=A)
    return null
  }

  const ordered = [...participants].sort(orderParticipants)
  const busy = pending !== null || confirmation !== null

  /**
   * Единое выполнение действия: нейтральный отказ по коду, без локального
   * отката; извлечение кода переопределяется для гостевой ссылки (№52/№53
   * несут guestInviteErrorCode вместо ModerationErrorCode).
   */
  async function run(
    action: ModerationAction,
    call: () => Promise<unknown>,
    codeOf: (
      cause: unknown,
    ) => ModerationErrorCode | GuestInviteErrorCode | null = moderationErrorCode,
  ): Promise<void> {
    setNotice(null)
    setPending(action)
    try {
      await call()
    } catch (cause) {
      setNotice(moderationNotice(codeOf(cause)))
    } finally {
      setPending(null)
    }
  }

  /** №49: полное SET-состояние из текущих grants цели с переключённым полем. */
  function flipRestriction(identity: string, field: RestrictionField): void {
    const target = participants.find((participant) => participant.identity === identity)
    if (target === undefined) {
      return
    }
    const current = restrictionMarks(target.permissions)
    const next: UpdateParticipantRestrictionsRequest = {
      micRestricted: current.micRestricted,
      cameraRestricted: current.cameraRestricted,
      screenRestricted: current.screenRestricted,
      speechRestricted: current.speechRestricted,
      [field]: !current[field],
    }
    void run({ kind: 'restrictions', identity, field }, () =>
      updateParticipantRestrictions(conferenceId, identity, next),
    )
  }

  /** №50: противоположная роль цели (назначение/снятие симметричны, Q21=A). */
  function flipRole(identity: string): void {
    const target = participants.find((participant) => participant.identity === identity)
    if (target === undefined) {
      return
    }
    const nextRole: ConferenceParticipantRole =
      parseParticipantMarks(target.metadata).role === 'moderator' ? 'participant' : 'moderator'
    void run({ kind: 'role', identity }, () =>
      updateParticipantRole(conferenceId, identity, nextRole),
    )
  }

  /** №51: кик после явного подтверждения (деструктивное действие). */
  function kick(identity: string): void {
    void run({ kind: 'kick', identity }, () => kickParticipant(conferenceId, identity))
  }

  /**
   * №52: генерация ссылки — первая и повторная той же кнопкой (сервер
   * атомарно отзывает предыдущую, Q17=A — клиент ничего не вычисляет).
   */
  function inviteGuest(): void {
    setCopied(false)
    void run(
      { kind: 'invite' },
      async () => {
        setGuestInvite(await createGuestInvite(conferenceId))
      },
      guestInviteErrorCode,
    )
  }

  /**
   * №53: отзыв после явного подтверждения (деструктивное — отключение
   * гостей, Q13=B); `404 guest_invite_not_found` — идемпотентный успех
   * (ссылку мог отозвать другой модератор, Q21=A): молчаливый коллапс.
   */
  function revokeInvite(): void {
    void run(
      { kind: 'revoke_invite' },
      async () => {
        try {
          await revokeGuestInvite(conferenceId)
        } catch (cause) {
          if (guestInviteErrorCode(cause) !== 'guest_invite_not_found') {
            throw cause
          }
        }
        setGuestInvite(null)
        setCopied(false)
      },
      guestInviteErrorCode,
    )
  }

  /**
   * «Копировать» — локальное действие без REST-вызова (pending не
   * поднимает; кнопка следует общему busy блока); сбой клипборда —
   * молча, ручное выделение readonly-поля остаётся страховкой.
   */
  async function copyInvite(): Promise<void> {
    if (guestInvite === null) {
      return
    }
    setCopied(false)
    try {
      await navigator.clipboard.writeText(guestInvite.inviteUrl)
      setCopied(true)
    } catch {
      // без статуса: поле доступно для ручного выделения
    }
  }

  /** №48: завершение после явного подтверждения (деструктивное действие). */
  function end(): void {
    void run({ kind: 'end' }, () => endConference(conferenceId))
  }

  /** Подпись срока ссылки (локальное время; null — невалидный expiresAt). */
  const expiresLabel = guestInvite === null ? null : inviteExpiresLabel(guestInvite.expiresAt)

  return (
    <section className="conference-moderation" aria-labelledby={titleId}>
      <h2 className="conference-moderation-title" id={titleId}>
        Модерация
      </h2>
      <ul className="conference-moderation-list">
        {ordered.map((participant) => (
          <ModerationRow
            key={participant.identity}
            participant={participant}
            disabled={busy}
            onRestriction={flipRestriction}
            onRole={flipRole}
            onKick={(identity, label) => {
              setConfirmation({ kind: 'kick', identity, label })
            }}
          />
        ))}
      </ul>
      <fieldset className="conference-guest-invite" aria-label="Гостевая ссылка">
        <button
          type="button"
          className="conference-guest-invite-generate"
          disabled={busy}
          onClick={inviteGuest}
        >
          Пригласить гостя
        </button>
        {guestInvite !== null && (
          <>
            {/* Q17=A/Q13=B: генерация отзывает предыдущую — заранее о последствиях */}
            <p className="conference-guest-invite-hint">
              Новая ссылка отзовёт предыдущую — вошедшие по ней гости отключатся.
            </p>
            <input
              className="conference-guest-invite-url"
              type="text"
              aria-label="Ссылка-приглашение"
              readOnly
              aria-readonly="true"
              value={guestInvite.inviteUrl}
            />
            {expiresLabel !== null && (
              <p className="conference-guest-invite-expires">Действует {expiresLabel}</p>
            )}
            <div className="conference-guest-invite-actions">
              <button
                type="button"
                className="conference-guest-invite-copy"
                disabled={busy}
                onClick={() => {
                  void copyInvite()
                }}
              >
                Копировать
              </button>
              <button
                type="button"
                className="conference-guest-invite-revoke conference-moderation-button-danger"
                disabled={busy}
                onClick={() => {
                  setConfirmation({ kind: 'revoke_invite' })
                }}
              >
                Отозвать ссылку
              </button>
            </div>
            {copied && (
              <output className="conference-guest-invite-copied" aria-live="polite">
                Скопировано
              </output>
            )}
          </>
        )}
      </fieldset>
      <button
        type="button"
        className="conference-moderation-end"
        disabled={busy}
        onClick={() => {
          setConfirmation({ kind: 'end' })
        }}
      >
        Завершить конференцию
      </button>
      {notice !== null && <output className="conference-moderation-notice">{notice}</output>}
      {confirmation !== null && (
        <div
          className="conference-moderation-confirm"
          role="alertdialog"
          aria-labelledby={dialogTitleId}
          aria-modal="false"
        >
          <p className="conference-moderation-confirm-text" id={dialogTitleId}>
            {confirmationText(confirmation)}
          </p>
          <div className="conference-moderation-confirm-actions">
            <button
              type="button"
              className="conference-moderation-button-danger"
              disabled={pending !== null}
              onClick={() => {
                const confirmed = confirmation
                setConfirmation(null)
                if (confirmed.kind === 'kick') {
                  kick(confirmed.identity)
                } else if (confirmed.kind === 'revoke_invite') {
                  revokeInvite()
                } else {
                  end()
                }
              }}
            >
              Подтвердить
            </button>
            <button
              type="button"
              className="conference-moderation-button"
              disabled={pending !== null}
              autoFocus
              onClick={() => {
                setConfirmation(null)
              }}
            >
              Отмена
            </button>
          </div>
        </div>
      )}
    </section>
  )
}
