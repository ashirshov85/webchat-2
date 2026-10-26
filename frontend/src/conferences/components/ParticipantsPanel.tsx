/**
 * Панель участников конференции — ЕДИНЫЙ список (009, US3, T107;
 * FR-008; прототипы tmp/conference-page-{moderator,participant}.html,
 * при расхождениях побеждает прототип): объединение панелей
 * участников (T041) и модерации (T042) — дублирующий второй список и
 * строки кнопок демонтированы, панель модерации как компонент
 * упразднена. Рендерится в слоте sidebar ConferenceRoom (внутри
 * RoomContext, правая колонка .conference-side T106), источник
 * истины присутствия — useParticipants ([local, ...remote], T027);
 * заголовок «Участники (N)» живой.
 *
 * Строка: аватар (T073, ParticipantAvatar — ui/Avatar, «как в
 * контактах», 34px design-tokens §4) + имя (fallback — identity, как
 * метки тайлов T027) + «вы» у локального + роль ТОЛЬКО пометками
 * «гость»/«модератор» (обычный участник — без пометки; лексика
 * пометок тайлов T100: курсив --muted с разделителем «·»).
 * identity-строка и текстовые заметки ограничений T041/T094 из
 * строки убраны (T107): identity остаётся ключом действий модерации
 * в data-participant-identity, истинное состояние источников строки
 * несут индикаторы T108 (тексты ограничений живут в подписях пунктов
 * кебаб-меню и tooltip'ах тумблеров T098).
 *
 * Индикаторы источников строки (T108; прототипы
 * tmp/conference-page-{moderator,participant}.html, при расхождениях
 * побеждает прототип): ТРИ латунных мини-бейджа 22px (микрофон/
 * камера/экран, инлайн-SVG приборов лексики T098/T100) между именем
 * и кебабом. У обычного участника РОВНО ДВА состояния — вкл (золото +
 * on-части иконки) / выкл (приглушение + диагональный штрих --err);
 * у модератора ТРИ — третье «отключено модератором» (приглушение
 * saturate + замок-бейдж, лексика blocked тумблеров T098) выводится
 * ТОЛЬКО в виду модератора: жертва и прочие участники читают
 * блокировку как обычное «выкл» (прототип участника). Состояние —
 * проекция LiveKit: публикация источника (механика micOn T100:
 * local — isMicrophoneEnabled/isCameraEnabled/isScreenShareEnabled
 * useLocalParticipant, remote — publication.isMuted; экран remote —
 * screen-реф слитого тайла T084) + ограничение (grants
 * restrictionMarks с поглощением мастером Q4=A: действует
 * ограничение → «отключено модератором», источник опубликован и не
 * замучен → «вкл», иначе «выкл»). Индикаторы НЕ кликабельны (span,
 * pointer-events: none) и БЕЗ tooltip; состояние дублируется
 * visually-hidden текстом «микрофон включён/выключен/отключён
 * модератором» и т.п. (конвенция 008).
 *
 * Действия модератора (T107): у КАЖДОЙ строки модератора — включая
 * свою и других модераторов (равноправие Q21=A/№50) — кебаб-кнопка
 * `.c-menu` (три точки, лексика GroupMembersModal/contacts-modal),
 * открывающая ui/ContextMenu (портал в body, ctx-item/ctx-sep/
 * danger): точечные «Отключить/Включить микрофон/камеру/экран» №49
 * (подпись — от текущего SET-состояния цели по projection grants
 * restrictionMarks), мастер «Отключить/Включить все источники»
 * (доменное speechRestricted, T094, поглощение Q4=A — под нажатым
 * мастером точечные пункты не вычисляют SET: гвард без вызова),
 * «Назначить модератором»/«Снять модератора» №50 и «Исключить»
 * danger №51 (подтверждение ниже). Модерация ТОЛЬКО серверная
 * (FR-008): каждый пункт — REST-вызов контрольной плоскости ≤ 2 c
 * (SC-005), панель не хранит ограничений локально — live-строки
 * обновляются permission/metadata-событиями сами.
 *
 * Футер панели — только модератору, ПОД списком и всегда видим
 * (скролл ТОЛЬКО у .conference-participants-list, футер flex:none):
 * разделитель + блок «Гостевая ссылка» (T081, №52/№53 как есть) +
 * «Завершить конференцию» (№48, T038). Отказ — нейтральная строка
 * role=status по коду Problem (moderator_required/
 * participant_not_found/conference_ended), без раскрытия деталей.
 * Деструктивные №51/№53/№48 — явное подтверждение в МОДАЛИ (T109):
 * единая оболочка ui/ModalShell (затемнение поверх комнаты, Esc,
 * фокус-ловушка, возврат фокуса на инициатора) + ui/ConfirmDialog
 * (кнопки «Отмена»/действие, danger-акцент) с текстами T042/T081 —
 * инлайн-блок в узком сайдбаре демонтирован (распирал список и
 * конфликтовал с прижатым футером). «В полёте» кебаби и кнопки
 * футера недоступны (без гонок повторных вызовов).
 *
 * Видимость инструментов — роль ЛОКАЛЬНОГО подключения из его
 * metadata (не №46-снимок pre-join): снятие/назначение №50 приходит
 * broadcast'ом metadata — кебаби и футер появляются/исчезают живьём,
 * список остаётся у всех. Дефолт битого/отсутствующего metadata —
 * «участник»: инструменты ошибочно не показываются (fail-closed).
 * У участника/гостя кебаба и футера нет — панель внешне та же
 * (паритет прототипа участника); гостевой паритет (GuestJoinPage)
 * монтирует ту же панель.
 *
 * Доступность — стандарты 008: список ul/li, заголовок панели
 * aria-labelledby, кебаб aria-haspopup="menu" + aria-expanded +
 * доступное имя «Действия с участником {имя}», фокус с клавиатуры —
 * ContextMenu (стрелки/Enter/Esc, возврат на инициатора) и
 * conferences.css.
 */
import { useLocalParticipant, useParticipants, useTracks } from '@livekit/components-react'
import { Track } from 'livekit-client'
import type { LocalParticipant, RemoteParticipant } from 'livekit-client'
import { Fragment, useId, useState } from 'react'
import type { ReactNode } from 'react'
import { ContextMenu, type MenuItem } from '../../ui/ContextMenu'
import { ConfirmDialog } from '../../ui/ConfirmDialog'
import { ModalShell } from '../../ui/ModalShell'
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
import { ParticipantAvatar } from './ParticipantAvatar'
import '../conferences.css'

/** Роль принципала конференции (metadata LiveKit-токена, §3). */
export type ConferenceParticipantRole = 'moderator' | 'participant'

/** Пометки участника из metadata токена/UpdateParticipant (§3). */
export interface ParticipantMarks {
  readonly role: ConferenceParticipantRole
  /** true у подключений по гостевой ссылке №55 (US4/T051). */
  readonly guest: boolean
}

/**
 * Индикация ограничений — зеркало ParticipantRestrictions (домен 009)
 * по фактическим grants подключения: точечные restricted-поля и
 * «Все источники» (доменное speechRestricted, T094; Q4=A).
 * Строка списка текстовых заметок не несёт (T107) — проекция
 * остаётся источником подписей кебаб-меню и гейтов MediaControls.
 */
export interface ParticipantRestrictionMarks {
  readonly micRestricted: boolean
  readonly cameraRestricted: boolean
  readonly screenRestricted: boolean
  readonly speechRestricted: boolean
}

const MARKS_DEFAULT: ParticipantMarks = { role: 'participant', guest: false }

const RESTRICTIONS_NONE: ParticipantRestrictionMarks = {
  micRestricted: false,
  cameraRestricted: false,
  screenRestricted: false,
  speechRestricted: false,
}

/**
 * Wire-константы TrackSource из @livekit/protocol (CAMERA=1,
 * MICROPHONE=2, SCREEN_SHARE=3) — прото-enum стабилен контрактом;
 * без прямой зависимости: @livekit/protocol — транзитивный пакет
 * livekit-client, приложение его не объявляет.
 */
const WIRE_SOURCE_CAMERA = 1
const WIRE_SOURCE_MICROPHONE = 2
const WIRE_SOURCE_SCREEN_SHARE = 3

/**
 * Publish-сторона прав LiveKit (структурный тип protocol
 * ParticipantPermission): панели достаточно canPublish/canPublishSources.
 */
interface PublishPermissions {
  readonly canPublish: boolean
  readonly canPublishSources: readonly number[]
}

/**
 * Порядок строк: локальный первым, затем удалённые по identity (стабильный DOM-порядок).
 */
// eslint-disable-next-line react-refresh/only-export-components -- чистая проекция без состояния/хуков, разделяется с MediaControls (T028)
export function orderParticipants(
  left: LocalParticipant | RemoteParticipant,
  right: LocalParticipant | RemoteParticipant,
): number {
  if (left.isLocal !== right.isLocal) {
    return left.isLocal ? -1 : 1
  }
  if (left.identity !== right.identity) {
    return left.identity < right.identity ? -1 : 1
  }
  return 0
}

/** Отображаемое имя строки: name тикета/метаданных, иначе identity (как тайлы T027). */
// eslint-disable-next-line react-refresh/only-export-components -- чистая проекция без состояния/хуков
export function participantLabel(name: string | undefined, identity: string): string {
  return name !== undefined && name.trim() !== '' ? name : identity
}

/**
 * Пометки из metadata участника: JSON `{"role":…,"guest":…}` (§3) —
 * ставится минтом токена и обновляется UpdateParticipant (broadcast
 * ролей, Q9=A). Отсутствие/битый JSON — нейтральный дефолт «участник,
 * не гость»: список не рушится посторонними метаданными, инструменты
 * модератора не показываются (fail-closed). Локальная роль — тот же
 * источник (гейт кебабов/футера, T107).
 */
// eslint-disable-next-line react-refresh/only-export-components -- чистая проекция без состояния/хуков
export function parseParticipantMarks(metadata: string | undefined): ParticipantMarks {
  if (metadata === undefined) {
    return MARKS_DEFAULT
  }
  try {
    const parsed: unknown = JSON.parse(metadata)
    if (typeof parsed !== 'object' || parsed === null) {
      return MARKS_DEFAULT
    }
    const record = parsed as Record<string, unknown>
    return {
      role: record.role === 'moderator' ? 'moderator' : 'participant',
      guest: record.guest === true,
    }
  } catch {
    return MARKS_DEFAULT
  }
}

/**
 * Индикация ограничений по фактическим grants (§3 — зеркало
 * ParticipantRestrictions.grants): `canPublish=false` — ВСЕ ЧЕТЫРЕ
 * метки true («всё заблокировано»: покрывает и «Все источники»
 * (доменное speechRestricted, T094; Q4=A), и все-три-точечных T094 —
 * grants эти доменные состояния неразличимы, эффективное состояние
 * одинаково; осознанная неразличимость);
 * иначе точечно — источника нет в canPublishSources ⟺ срезан
 * модератором. Пустой набор источников при canPublish=true =
 * «все разрешены» (семантика LiveKit) — защитная ветка без пометок
 * (после фикса grants T094 из домена не возникает). Подписи пунктов
 * кебаб-меню №49 (T107) и гейты MediaControls выводятся из тех же
 * grants (источник истины — LiveKit, SC-005).
 */
// eslint-disable-next-line react-refresh/only-export-components -- чистая проекция без состояния/хуков
export function restrictionMarks(
  permissions: PublishPermissions | undefined,
): ParticipantRestrictionMarks {
  if (permissions === undefined) {
    return RESTRICTIONS_NONE
  }
  if (!permissions.canPublish) {
    return {
      micRestricted: true,
      cameraRestricted: true,
      screenRestricted: true,
      speechRestricted: true,
    }
  }
  const sources = permissions.canPublishSources
  if (sources.length === 0) {
    return RESTRICTIONS_NONE
  }
  return {
    micRestricted: !sources.includes(WIRE_SOURCE_MICROPHONE),
    cameraRestricted: !sources.includes(WIRE_SOURCE_CAMERA),
    screenRestricted: !sources.includes(WIRE_SOURCE_SCREEN_SHARE),
    speechRestricted: false,
  }
}

export interface ParticipantsPanelProps {
  /** Адрес REST-действий №48–№53 (идентификатор конференции). */
  readonly conferenceId: string
}

/** Источник строки для индикатора T108. */
type SignalSource = 'mic' | 'cam' | 'screen'

/** Состояние индикатора источника строки (T108): вкл/выкл/отключено модератором. */
type SourceSignalState = 'on' | 'off' | 'blocked'

/** Точечное поле ограничения источника (мастер — speechRestricted, T094). */
const SIGNAL_RESTRICTION: Record<SignalSource, PointRestrictionField> = {
  mic: 'micRestricted',
  cam: 'cameraRestricted',
  screen: 'screenRestricted',
}

/** Порядок бейджей в строке: микрофон → камера → экран (прототип T108). */
const SIGNAL_SOURCES: readonly SignalSource[] = ['mic', 'cam', 'screen']

/**
 * Тексты состояний для visually-hidden строк (конвенция 008) —
 * «отключено модератором» существует только в виду модератора,
 * не-модератору блокировка проецируется как обычное «выкл».
 */
const SIGNAL_TEXT: Record<SignalSource, Record<SourceSignalState, string>> = {
  mic: {
    on: 'микрофон включён',
    off: 'микрофон выключен',
    blocked: 'микрофон отключён модератором',
  },
  cam: {
    on: 'камера включена',
    off: 'камера выключена',
    blocked: 'камера отключена модератором',
  },
  screen: {
    on: 'экран включён',
    off: 'экран выключен',
    blocked: 'экран отключён модератором',
  },
}

/* ---------------------------------------------------------------
 * Инлайн-SVG приборов из прототипа T108 (лексика иконок T098/T100):
 * on-части (волны/ирис/луч) проявляются в data-state='on',
 * диагональный штрих — в 'off', замок-бейдж — в 'blocked' (CSS).
 * --------------------------------------------------------------- */

const SIGNAL_MIC_ICON = (
  <svg viewBox="0 0 32 32" focusable="false">
    <rect x="12" y="4.5" width="8" height="11" rx="4" />
    <path d="M9 11.5v.8a7 7 0 0 0 14 0v-.8" />
    <path d="M16 19.3v3M12.6 23.2h6.8" />
    <path className="cp-sig-on" d="M5.4 13.2a4.2 4.2 0 0 0 0 5.6" />
    <path className="cp-sig-on" d="M26.6 13.2a4.2 4.2 0 0 1 0 5.6" />
    <line className="cp-sig-slash" x1="6" y1="26" x2="26" y2="6" />
  </svg>
)

const SIGNAL_CAM_ICON = (
  <svg viewBox="0 0 32 32" focusable="false">
    <path d="M12 7.5v-.9a4 4 0 0 1 8 0v.9" />
    <rect x="5.8" y="7.5" width="20.4" height="15" rx="2.6" />
    <circle cx="16" cy="15" r="4.6" />
    <circle className="cp-sig-on" cx="16" cy="15" r="1.7" />
    <path className="cp-sig-on" d="M14.6 13.6a2 2 0 0 1 1.6-.5" />
    <path d="M9.5 22.5v1.8M22.5 22.5v1.8" />
    <line className="cp-sig-slash" x1="5" y1="27" x2="27" y2="5" />
  </svg>
)

const SIGNAL_SCREEN_ICON = (
  <svg viewBox="0 0 32 32" focusable="false">
    <rect x="3.2" y="10.8" width="8.6" height="7" rx="1.6" />
    <circle cx="7.5" cy="8.2" r="2.1" />
    <path d="M7.5 10.3v.5" />
    <rect x="11.8" y="12.6" width="2.6" height="3" rx="1" />
    <rect x="17.8" y="6.2" width="10.8" height="7.6" rx="1.2" />
    <path d="M23.2 13.8v2.6M20.6 16.6h5.2" />
    <path className="cp-sig-on" d="M14.6 12.2 17.6 9.2M14.6 16 17.6 13.4" />
    <path className="cp-sig-on" d="M20 8.4h6.4M20 10.6h4" />
    <line className="cp-sig-slash" x1="5" y1="27" x2="27" y2="5" />
  </svg>
)

const SIGNAL_ICONS: Record<SignalSource, ReactNode> = {
  mic: SIGNAL_MIC_ICON,
  cam: SIGNAL_CAM_ICON,
  screen: SIGNAL_SCREEN_ICON,
}

/** Замок-бейдж «отключено модератором» (виден в data-state='blocked'). */
const SIGNAL_LOCK_BADGE = (
  <span className="cp-sig-lock">
    <svg viewBox="0 0 16 16" focusable="false">
      <rect x="3.5" y="7" width="9" height="6.5" rx="1.2" />
      <path d="M5.5 7V5.2a2.5 2.5 0 0 1 5 0V7" />
    </svg>
  </span>
)

/** Точечное поле ограничения №49, переключаемое пунктом меню (мастер — speechRestricted, T094). */
type PointRestrictionField = 'micRestricted' | 'cameraRestricted' | 'screenRestricted'

/** Поле ограничения №49, переключаемое пунктом меню. */
type RestrictionField = keyof UpdateParticipantRestrictionsRequest

/** Действие в полёте (кебаби и кнопки футера недоступны до завершения). */
type ModerationAction =
  | { readonly kind: 'restrictions'; readonly identity: string; readonly field: RestrictionField }
  | { readonly kind: 'role'; readonly identity: string }
  | { readonly kind: 'kick'; readonly identity: string }
  | { readonly kind: 'invite' }
  | { readonly kind: 'revoke_invite' }
  | { readonly kind: 'end' }

/** Ожидаемое подтверждение деструктивного действия (модаль T109). */
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

/**
 * Параметры модального подтверждения деструктивного действия (T109):
 * заголовок оболочки, текст T042/T081 без изменений и подпись кнопки
 * действия (danger-акцент ConfirmDialog).
 */
function confirmationUi(confirmation: ModerationConfirmation): {
  readonly title: string
  readonly text: string
  readonly confirmLabel: string
} {
  if (confirmation.kind === 'end') {
    return {
      title: 'Завершить конференцию',
      text: 'Завершить конференцию для всех? Все участники отключатся немедленно.',
      confirmLabel: 'Завершить',
    }
  }
  if (confirmation.kind === 'revoke_invite') {
    return {
      title: 'Отозвать гостевую ссылку',
      text: 'Отозвать гостевую ссылку? Все вошедшие по ней гости отключатся немедленно, новые входы блокируются.',
      confirmLabel: 'Отозвать',
    }
  }
  return {
    title: 'Исключить участника',
    text: `Исключить ${confirmation.label} из конференции? Участник отключится немедленно и сможет вернуться по действующей ссылке.`,
    confirmLabel: 'Исключить',
  }
}

interface ParticipantRowProps {
  readonly participant: LocalParticipant | RemoteParticipant
  /** Кебаб рендерится только модератору (T107), включая свою строку (Q21=A). */
  readonly moderator: boolean
  /** Состояния индикаторов источников строки (T108) — проекция панели. */
  readonly signals: Readonly<Record<SignalSource, SourceSignalState>>
  /** Все кебаби недоступны, пока летит любое действие панели. */
  readonly disabled: boolean
  /** Строка цели открытого меню (aria-expanded её кебаба). */
  readonly menuIdentity: string | null
  readonly onMenuOpen: (identity: string, anchor: DOMRect) => void
}

/**
 * Строка участника: аватар + имя (+«вы») + пометки «гость»/
 * «модератор» + индикаторы источников (T108) + кебаб действий
 * модератора. Индикаторы — span-бейджи между именем и кебабом:
 * НЕ кликабельны и без tooltip, состояние дублируется
 * visually-hidden текстом (008); бейдж скрыт от дерева доступности
 * (aria-hidden), текст несёт соседняя строка. Кебаб — паттерн
 * .c-menu GroupMembersModal: aria-haspopup="menu" + aria-expanded,
 * доступное имя «Действия с участником {имя}»; клик по кебабу
 * гасится stopPropagation (строка — не кнопка, но паритет lexica).
 */
function ParticipantRow({
  participant,
  moderator,
  signals,
  disabled,
  menuIdentity,
  onMenuOpen,
}: ParticipantRowProps) {
  const marks = parseParticipantMarks(participant.metadata)
  const label = participantLabel(participant.name, participant.identity)

  return (
    <li className="conference-participants-item" data-participant-identity={participant.identity}>
      {/* Аватар перед именем (T073, «как в контактах»: лексика строк
          контактов, 34px design-tokens §4) — инициалы цепочки профиля
          для членов, фолбэк из name для гостей/вне снимка. */}
      <ParticipantAvatar participant={participant} size={34} />
      <div className="cp-main">
        <span className="conference-participants-name">{label}</span>
        {participant.isLocal && <span className="conference-participants-you">вы</span>}
        {/* Роль — ТОЛЬКО пометки «гость»/«модератор» (обычный участник
            без пометки), лексика пометок тайлов T100: «·»-разделитель */}
        {marks.guest && (
          <span className="conference-participants-mark" data-kind="guest">
            гость
          </span>
        )}
        {marks.role === 'moderator' && (
          <span className="conference-participants-mark" data-kind="role">
            модератор
          </span>
        )}
      </div>
      {/* Индикаторы источников (T108): микрофон/камера/экран, состояние —
          data-state on/off/blocked; замок виден только в blocked (в виду
          модератора), on-части и штрих управляются CSS по data-state */}
      <span className="cp-sigs">
        {SIGNAL_SOURCES.map((source) => {
          const state = signals[source]
          return (
            <Fragment key={source}>
              <span className="cp-sig" data-src={source} data-state={state} aria-hidden="true">
                {SIGNAL_ICONS[source]}
                {SIGNAL_LOCK_BADGE}
              </span>
              {/* 008: состояние не только цветом — бейдж скрыт от дерева
                  доступности, текст несёт соседняя строка */}
              <span className="visually-hidden" data-src-text={source}>
                {SIGNAL_TEXT[source][state]}
              </span>
            </Fragment>
          )
        })}
      </span>
      {moderator && (
        <button
          type="button"
          className="c-menu"
          title="Действия с участником"
          aria-haspopup="menu"
          aria-expanded={menuIdentity === participant.identity}
          aria-label={`Действия с участником ${label}`}
          disabled={disabled}
          onClick={(event) => {
            event.stopPropagation()
            onMenuOpen(participant.identity, event.currentTarget.getBoundingClientRect())
          }}
        >
          <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
            <circle cx="12" cy="5" r="1.8" />
            <circle cx="12" cy="12" r="1.8" />
            <circle cx="12" cy="19" r="1.8" />
          </svg>
        </button>
      )}
    </li>
  )
}

/**
 * Панель участников: единый список подключённых конференции из
 * LiveKit (имя/«вы»/пометки роли) + инструменты модератора (кебаб
 * строк №49–№51, футер №52/№53/№48) — только роли модератора
 * локального подключения, live по broadcast'у metadata (Q9=A).
 */
export default function ParticipantsPanel({ conferenceId }: ParticipantsPanelProps) {
  const titleId = useId()
  const { localParticipant, isMicrophoneEnabled, isCameraEnabled, isScreenShareEnabled } =
    useLocalParticipant()
  const participants = useParticipants()
  // Экран remote-участников (T108): живая демонстрация — screen-реф
  // слитого тайла T084 (useTracks ScreenShare; завершение демонстрации
  // снимает трек с публикации — рефа нет, «выкл»). Свой экран —
  // isScreenShareEnabled ниже (канонический источник, T098).
  const screenRefs = useTracks([Track.Source.ScreenShare], { onlySubscribed: false })
  const screenOnIdentities = new Set(screenRefs.map((ref) => ref.participant.identity))
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
  /** Строка открытого кебаб-меню (identity) + якорь её кнопки. */
  const [menuIdentity, setMenuIdentity] = useState<string | null>(null)
  const [menuAnchor, setMenuAnchor] = useState<DOMRect | null>(null)

  const localMarks = parseParticipantMarks(localParticipant.metadata)
  const moderator = localMarks.role === 'moderator'
  const ordered = [...participants].sort(orderParticipants)
  // Гейт «в полёте» — ТОЛЬКО pending: открытая модаль T109 сама
  // блокирует панель (затемнение + фокус-ловушка ModalShell), а
  // дизейблинг инициатора в момент открытия рвал бы возврат фокуса
  // (ModalShell захватывает активный элемент — дизейбл в том же
  // коммите оставил бы body вместо кебаба/кнопки футера)
  const busy = pending !== null

  /**
   * Состояния индикаторов строки (T108): вкл = источник опубликован и
   * не замучен (local — isXxxEnabled, remote mic/cam — publication
   * .isMuted, remote экран — screen-реф T084); действует ограничение
   * (точечное или мастер speechRestricted, поглощение Q4=A) —
   * «отключено модератором» ТОЛЬКО в виду модератора, иначе «выкл».
   * Реактивность — useParticipants/useTracks перевыпускают рефы на
   * TrackMuted/TrackPublished/ParticipantPermissionsChanged.
   */
  function signalsOf(
    participant: LocalParticipant | RemoteParticipant,
    viewerModerator: boolean,
  ): Readonly<Record<SignalSource, SourceSignalState>> {
    const marks = restrictionMarks(participant.permissions)
    const restrictedOf = (source: SignalSource): boolean =>
      marks.speechRestricted || marks[SIGNAL_RESTRICTION[source]]
    const publishedOf = (source: SignalSource): boolean => {
      if (participant.isLocal) {
        if (source === 'mic') {
          return isMicrophoneEnabled
        }
        return source === 'cam' ? isCameraEnabled : isScreenShareEnabled
      }
      if (source === 'screen') {
        return screenOnIdentities.has(participant.identity)
      }
      // T090/T100-механика remote: замученная публикация = «выкл»
      // (выключение камеры — mute трека, серверный мьют №49 — isMuted);
      // Track.Source — строковый enum livekit-client ('microphone'/
      // 'camera' — WIRE-константы выше только для canPublishSources)
      const publication = participant.getTrackPublication(
        source === 'mic' ? Track.Source.Microphone : Track.Source.Camera,
      )
      return publication !== undefined && publication.isMuted !== true
    }
    const stateOf = (source: SignalSource): SourceSignalState => {
      if (restrictedOf(source)) {
        return viewerModerator ? 'blocked' : 'off'
      }
      return publishedOf(source) ? 'on' : 'off'
    }
    return { mic: stateOf('mic'), cam: stateOf('cam'), screen: stateOf('screen') }
  }

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

  /**
   * №49 точечный пункт (мастер отжат — ветка canPublish=true проекции
   * точна, T094): полное SET-состояние из текущих grants цели с
   * переключённым одним полем. Под нажатым мастером SET из
   * неоднозначного состояния не вычисляется — гвард без вызова
   * (поглощение Q4=A, управление мастером).
   */
  function flipPointRestriction(identity: string, field: PointRestrictionField): void {
    const target = participants.find((participant) => participant.identity === identity)
    if (target === undefined) {
      return
    }
    const current = restrictionMarks(target.permissions)
    if (current.speechRestricted) {
      return
    }
    const next: UpdateParticipantRestrictionsRequest = {
      micRestricted: current.micRestricted,
      cameraRestricted: current.cameraRestricted,
      screenRestricted: current.screenRestricted,
      speechRestricted: false,
      [field]: !current[field],
    }
    void run({ kind: 'restrictions', identity, field }, () =>
      updateParticipantRestrictions(conferenceId, identity, next),
    )
  }

  /**
   * №49 мастер «Все источники» (T094): нажатие нормализует точки в
   * speech (SET {speechRestricted:true, точки false} — доменное
   * speechRestricted Q4=A поглощает точечные); отжатие включает всё
   * (SET все четыре false).
   */
  function toggleMasterRestriction(identity: string): void {
    const target = participants.find((participant) => participant.identity === identity)
    if (target === undefined) {
      return
    }
    const next: UpdateParticipantRestrictionsRequest = restrictionMarks(target.permissions)
      .speechRestricted
      ? {
          micRestricted: false,
          cameraRestricted: false,
          screenRestricted: false,
          speechRestricted: false,
        }
      : {
          micRestricted: false,
          cameraRestricted: false,
          screenRestricted: false,
          speechRestricted: true,
        }
    void run({ kind: 'restrictions', identity, field: 'speechRestricted' }, () =>
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

  /**
   * Состав кебаб-меню строки (T107): подписи №49 — от текущего
   * SET-состояния цели (restrictionMarks): действующее ограничение →
   * «Включить …», иначе «Отключить …»; мастер — доменное
   * speechRestricted; №50 — противоположная роль цели; №51 — danger с
   * целью в доступном имени. Действие ≤ 2 c (SC-005).
   */
  function menuItemsOf(identity: string): readonly MenuItem[] {
    const target = participants.find((participant) => participant.identity === identity)
    if (target === undefined) {
      return []
    }
    const marks = parseParticipantMarks(target.metadata)
    const restrictions = restrictionMarks(target.permissions)
    const label = participantLabel(target.name, target.identity)
    const point = (
      field: PointRestrictionField,
      onLabel: string,
      offLabel: string,
    ): { readonly field: PointRestrictionField; readonly label: string } => ({
      field,
      label: restrictions[field] ? onLabel : offLabel,
    })
    const points = [
      point('micRestricted', 'Включить микрофон', 'Отключить микрофон'),
      point('cameraRestricted', 'Включить камеру', 'Отключить камеру'),
      point('screenRestricted', 'Включить экран', 'Отключить экран'),
    ]
    return [
      ...points.map(({ field, label: itemLabel }): MenuItem => ({
        label: itemLabel,
        onSelect: () => {
          flipPointRestriction(identity, field)
        },
      })),
      // Мастер «Все источники» — доменное speechRestricted №49 (T094)
      {
        label: restrictions.speechRestricted ? 'Включить все источники' : 'Отключить все источники',
        sepBefore: true,
        onSelect: () => {
          toggleMasterRestriction(identity)
        },
      },
      // №50: равноправие модераторов (Q21=A) — смена роли доступна любой строке
      {
        label: marks.role === 'moderator' ? 'Снять модератора' : 'Назначить модератором',
        sepBefore: true,
        onSelect: () => {
          flipRole(identity)
        },
      },
      // №51 — danger-пункт; выполняется только через подтверждение
      {
        label: 'Исключить',
        ariaLabel: `Исключить ${label}`,
        danger: true,
        sepBefore: true,
        onSelect: () => {
          setConfirmation({
            kind: 'kick',
            identity,
            label: target.isLocal ? `${label} (вы)` : label,
          })
        },
      },
    ]
  }

  return (
    <section className="conference-participants" aria-labelledby={titleId}>
      <h2 className="conference-participants-title" id={titleId}>
        Участники ({ordered.length})
      </h2>
      <ul className="conference-participants-list">
        {ordered.map((participant) => (
          <ParticipantRow
            key={participant.identity}
            participant={participant}
            moderator={moderator}
            signals={signalsOf(participant, moderator)}
            disabled={busy}
            menuIdentity={menuIdentity}
            onMenuOpen={(identity, anchor) => {
              setMenuIdentity(identity)
              setMenuAnchor(anchor)
            }}
          />
        ))}
      </ul>
      {moderator && (
        // Футер инструментов панели (T107): ПОД списком, всегда видим —
        // прокрутка живёт ТОЛЬКО у .conference-participants-list (CSS:
        // flex:none у футера), при длинном списке инструменты на месте
        <>
          <hr className="conference-participants-divider" />
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
        </>
      )}
      <ContextMenu
        open={menuIdentity !== null}
        anchor={menuIdentity !== null ? menuAnchor : null}
        items={menuIdentity !== null ? menuItemsOf(menuIdentity) : []}
        align="end"
        onClose={() => {
          setMenuIdentity(null)
        }}
      />
      {/* Подтверждение деструктивного №51/№53/№48 — модаль поверх
          комнаты (T109): единая оболочка 008 (затемнение, Esc, фокус-
          ловушка, возврат фокуса на инициатора — кебаб/кнопку футера),
          тексты T042/T081; закрытие любым путём оболочки = отмена
          (действие — только кнопкой в модали, danger-акцент) */}
      <ModalShell
        formId={confirmation !== null ? 'confirm' : null}
        title={confirmation !== null ? confirmationUi(confirmation).title : ''}
        onClose={() => {
          setConfirmation(null)
        }}
      >
        {confirmation !== null && (
          <ConfirmDialog
            text={confirmationUi(confirmation).text}
            confirmLabel={confirmationUi(confirmation).confirmLabel}
            variant="danger"
            onConfirm={() => {
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
            onCancel={() => {
              setConfirmation(null)
            }}
          />
        )}
      </ModalShell>
    </section>
  )
}
