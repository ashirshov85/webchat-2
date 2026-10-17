/**
 * Панель участников конференции (009, US3, T041; FR-008): список
 * подключённых участников из медиа-плоскости LiveKit — рендерится в
 * слоте children ConferenceRoom (внутри RoomContext), источник истины
 * присутствия — useParticipants ([local, ...remote], T027). Строки
 * обновляются живьём: дефолтные события хука включают приход/ уход
 * участников, ParticipantMetadataChanged (broadcast ролей) и
 * ParticipantPermissionsChanged (применение ограничений) — оба пишутся
 * серверным UpdateParticipant (livekit-integration §4, SC-005).
 *
 * Строка: имя (fallback — identity, как метки тайлов T027), пометка
 * «вы» у локального, роль из metadata токена/UpdateParticipant
 * (`{"role":…,"guest":…}`, §3), индикатор online (в списке только
 * подключённые — присутствие и есть online), identity без PII
 * (`u:{userId}:{connId}` / `g:{connId}`) — ключ действий модерации
 * №49–№51 (ModerationPanel T042); у локальной строки identity не
 * повторяется — блок joined уже показывает его (T026). Пометка «гость»
 * — metadata.guest=true подключений по гостевой ссылке №55 (T051):
 * имя гостя — введённое на GuestJoinPage (name токена минта),
 * fallback — identity `g:{connId}`.
 *
 * Индикация ограничений (FR-008, Q22=A) — зеркало доменных
 * ParticipantRestrictions по фактическим grants (§3):
 * `canPublish=false` — «право слова» (Q4=A: блокирует ВСЕ публикации,
 * источники при этом не консультируются); отсутствие источника в
 * `canPublishSources` — точечное ограничение; ПУСТОЙ набор источников
 * семантически «все разрешены» (LiveKit) — не помечается. Панель не
 * хранит состояний — это чистая проекция прав: ограничения применяются
 * сервером немедленно (SC-005) и воспроизводятся в новом минте при
 * переподключении (Q5=A).
 *
 * Доступность — стандарты 008: список ul/li, заголовок панели
 * aria-labelledby, индикаторы — текстом (не только цветом).
 */
import { useParticipants } from '@livekit/components-react'
import type { LocalParticipant, RemoteParticipant } from 'livekit-client'
import { useId } from 'react'
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
 * «право слова» (Q4=A).
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
 * ParticipantPermission): панелям достаточно canPublish/canPublishSources.
 */
interface PublishPermissions {
  readonly canPublish: boolean
  readonly canPublishSources: readonly number[]
}

/**
 * Порядок строк: локальный первым, затем удалённые по identity (стабильный DOM-порядок).
 * Общий для панелей US3 — ModerationPanel (T042) держит тот же порядок строк.
 */
// eslint-disable-next-line react-refresh/only-export-components -- чистая проекция без состояния/хуков, разделяется с ModerationPanel (T042)
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
// eslint-disable-next-line react-refresh/only-export-components -- чистая проекция без состояния/хуков, разделяется с ModerationPanel (T042)
export function participantLabel(name: string | undefined, identity: string): string {
  return name !== undefined && name.trim() !== '' ? name : identity
}

/**
 * Пометки из metadata участника: JSON `{"role":…,"guest":…}` (§3) —
 * ставится минтом токена и обновляется UpdateParticipant (broadcast
 * ролей, Q9=A). Отсутствие/битый JSON — нейтральный дефолт «участник,
 * не гость»: список не рушится посторонними метаданными. Экспортировано
 * для ModerationPanel (T042): локальная роль — тот же источник.
 */
// eslint-disable-next-line react-refresh/only-export-components -- чистая проекция без состояния/хуков, разделяется с ModerationPanel (T042)
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
 * ParticipantRestrictions.grants): «право слова» ⟺ canPublish=false
 * (источники не консультируются, Q4=A); иначе точечно — источника нет
 * в canPublishSources ⟺ срезан модератором. Пустой набор источников
 * = «все разрешены» (семантика LiveKit) — ограничений нет.
 * Экспортировано для ModerationPanel (T042): №49 SET-состояние
 * выводится из тех же grants (источник истины — LiveKit, SC-005).
 */
// eslint-disable-next-line react-refresh/only-export-components -- чистая проекция без состояния/хуков, разделяется с ModerationPanel (T042)
export function restrictionMarks(
  permissions: PublishPermissions | undefined,
): ParticipantRestrictionMarks {
  if (permissions === undefined) {
    return RESTRICTIONS_NONE
  }
  if (!permissions.canPublish) {
    return {
      micRestricted: false,
      cameraRestricted: false,
      screenRestricted: false,
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

/** Тексты индикаций ограничений строки («право слова» поглощает точечные — Q4=A). */
function restrictionNotes(marks: ParticipantRestrictionMarks): string[] {
  if (marks.speechRestricted) {
    return ['право слова']
  }
  const notes: string[] = []
  if (marks.micRestricted) {
    notes.push('микрофон отключён')
  }
  if (marks.cameraRestricted) {
    notes.push('камера отключена')
  }
  if (marks.screenRestricted) {
    notes.push('экран отключён')
  }
  return notes
}

/** Строка участника: чистая проекция состояния LiveKit (без локальных состояний). */
function ParticipantRow({
  participant,
}: {
  readonly participant: LocalParticipant | RemoteParticipant
}) {
  const marks = parseParticipantMarks(participant.metadata)
  const notes = restrictionNotes(restrictionMarks(participant.permissions))
  const label = participantLabel(participant.name, participant.identity)

  return (
    <li className="conference-participants-item" data-participant-identity={participant.identity}>
      <span className="conference-participants-name">{label}</span>
      {participant.isLocal ? (
        <span className="conference-participants-you">вы</span>
      ) : (
        // identity — ключ модерации; не дублируется, когда уже метка строки
        label !== participant.identity && (
          <span className="conference-participants-identity">{participant.identity}</span>
        )
      )}
      {marks.guest && <span className="conference-participants-guest">гость</span>}
      <span className="conference-participants-role">
        {marks.role === 'moderator' ? 'модератор' : 'участник'}
      </span>
      <span className="conference-participants-online">в сети</span>
      {notes.map((note) => (
        <span key={note} className="conference-participants-restricted">
          {note}
        </span>
      ))}
    </li>
  )
}

/**
 * Панель участников: список подключённых конференции из LiveKit
 * (identity/name, роль, online, ограничения; «гость» — №55/T051).
 * Рядом с ней в слоте children ConferenceRoom — MediaControls (T028)
 * и панель модерации (T042): действия №49–№51 адресуются по identity
 * строк.
 */
export default function ParticipantsPanel() {
  const titleId = useId()
  const participants = useParticipants()
  const ordered = [...participants].sort(orderParticipants)

  return (
    <section className="conference-participants" aria-labelledby={titleId}>
      <h2 className="conference-participants-title" id={titleId}>
        Участники ({ordered.length})
      </h2>
      <ul className="conference-participants-list">
        {ordered.map((participant) => (
          <ParticipantRow key={participant.identity} participant={participant} />
        ))}
      </ul>
    </section>
  )
}
