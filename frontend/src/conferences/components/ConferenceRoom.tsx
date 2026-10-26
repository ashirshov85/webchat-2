/**
 * Медиа-комната конференции (009, US1, T027; FR-004): подключение к
 * медиа-плоскости LiveKit по JoinTicket №47 (wsUrl + короткоживущий
 * токен) — Room livekit-client (simulcast + dynacast + adaptiveStream)
 * и рендер на примитивах @livekit/components-react (RoomContext →
 * useTracks/VideoTrack/RoomAudioRenderer + собственный тайл камеры
 * с аватаром-заглушкой T073, research §10: готовые компоненты вместо
 * ручного WebRTC-рендера — конституция VII). livekit-client остаётся
 * в ленивом чанке страницы (T002/T025 — main-bundle его не содержит).
 *
 * Жизненный цикл комнаты привязан к слоту «одна конференция на
 * вкладку» (Q10=A): bindConferenceDisconnect (useConference) делает
 * room.disconnect() частью корректного выхода — leave/pagehide/join
 * другой конференции той же вкладкой рвут медиа-подключение, остальные
 * участники видят выход (departure_timeout, livekit-integration §2).
 *
 * Room создаётся ЗАНОВО на каждый прогон эффекта подключения (паттерн
 * useLiveKitRoom @livekit/components-react): повторное использование
 * экземпляра после прерванного в полёте connect (StrictMode-перемонтаж
 * dev-сервера: cleanup рвёт подключение, повторный монтаж подключается
 * снова) оставляет Room в поломанном состоянии («skipping incoming
 * track after Room disconnected») — свежая комната на жизненный цикл
 * тикета надёжна и в dev, и в проде: смена ticket.token (реконнект,
 * FR-014) = новая комната, старая корректно разрывается cleanup'ом.
 *
 * Стартовая публикация — дефолты по mode конференции (№44: video →
 * камера+микрофон, audio — только микрофон, Q3=A soft); T034 (US2)
 * передаёт явное стартовое состояние из pre-join (оба выкл — «только
 * слушаю», FR-006) и стартовый выбор устройств (useDevices initial:
 * best-effort применение при появлении комнаты — ДО первой публикации,
 * поэтому микрофон/камера публикуются сразу выбранными на pre-join
 * устройствами; capture-дефолты комнаты настраиваются даже до connect,
 * смена комнаты при реконнекте применяет выбор заново). Отказ браузера
 * в доступе к устройствам не рушит
 * вход — участник остаётся слушателем с понятным предупреждением
 * (T032, Q6=A); повторный запрос доступа — уже в звонке тумблерами
 * MediaControls (T028): успешная публикация слота (LocalTrackPublished)
 * снимает свою часть предупреждения; ограничения права/источников уже
 * срезаны в grants токена сервером (FR-007) — публикация просто не
 * проходит. Своя демонстрация экрана публикуется тумблером
 * MediaControls (T028); экран и камера участника рендерятся СЛИТЫМ
 * тайлом (T084) — см. комментарий ConferenceTile ниже.
 *
 * Реконнект (T060, FR-014, Q15=B): подключённому LiveKit продлевает
 * токен сам (livekit-integration §3); транзиентная потеря связи —
 * внутренние Reconnecting/Reconnected livekit-client (индикация
 * «переподключение…», медиа-слой остаётся). При ПОЛНОМ разрыве после
 * успешного подключения открывается окно reconnect-window (дефолт
 * 120 с, conferences.reconnect-window Q19): цепочка ретраев с малым
 * интервалом, каждая попытка — СВЕЖИЙ тикет повторным №47 (члены) /
 * №55 (гости, refreshTicket страницы, FR-014: TTL 900 с < 4 ч —
 * истёкший токен не годится, минт даёт новый connId); смена
 * ticket.token пересоздаёт комнату — звонок продолжается. Истечение
 * окна — контролируемое завершение с понятным сообщением («lost»);
 * серийные разрывы одной цепочки держат ОДНО окно от первой потери
 * до восстановления. Серверные разрывы (RemoveParticipant/удаление
 * комнаты/дубль identity/нет прав) — терминальное «Вы отключены»
 * без авто-возврата: кик ≠ бан, но повторный вход — осознанное
 * действие по действующей ссылке (edge spec.md).
 *
 * Предупреждение о лимите длительности (T060 ← T056, FR-015,
 * Q16=B): шедулер шлёт admin-SendData пакет topic
 * `conference.warning`, payload `{"type":"conference.warning",
 * "endsAt":…}` — ВСЕМ подключённым, включая гостей без SSE; приём
 * RoomEvent.DataReceived по теме → тост «конференция завершится по
 * лимиту длительности» (закрывается вручную — событие одно, до
 * контролируемого завершения остаётся warning-before-end).
 *
 * Полноэкранный пин-оверлей (T079): живой пин (T074/T078) навешивает
 * маркер-класс conference-room-pinned на корневой div комнаты —
 * пин-состояние поднято в живой контур комнаты ConferenceRoomLive
 * (хукам тайлов нужна живая комната под RoomContext); CSS
 * (conferences.css) разворачивает комнату на весь вьюпорт браузера
 * без скроллбаров — семантика пина T074/T078 не меняется, слот
 * children (MediaControls/панели) остаётся в DOM и позиционируется
 * поверх тайла. Fullscreen API — вне scope (паритет T074/T075).
 *
 * Клавиатурный выход из пин-оверлея (T082, доступность 008): keydown
 * Escape при живом пине сворачивает закреплённый тайл — та же
 * семантика, что кнопка-тумблер «Свернуть»; слушатель на window
 * навешивается эффектом ТОЛЬКО в пин-состоянии и снимается при
 * сворачивании/размонтировании (пустой пин — Esc no-op без
 * обработчика). Esc — прикладная обработка, НЕ выход браузерного
 * fullscreen (Fullscreen API по-прежнему вне scope — паритет
 * T074/T075/T079) и НЕ выход из звонка (выход — MediaControls).
 *
 * Слитый тайл участника (T084): камера и демонстрация экрана одного
 * участника — ОДИН тайл (React-ключ identity, приоритет
 * screen > published-и-незамученная camera > аватар-заглушка T073:
 * выключение камеры — mute трека, НЕ unpublish, замученный
 * published-реф = аватар, T090): живое переключение
 * камера↔экран (T077 гасит камеру при старте демонстрации) меняет
 * содержимое ТОГО ЖЕ DOM-узла — сетка не перестраивается, второй
 * тайл не рождается; video-элемент на тайл один — VideoTrack
 * перевешивает attach при смене trackRef (кроссфейд из двух video
 * не вводится). Пин-ключ — identity: пин переживает переключения
 * камера↔экран в свёрнутом и развёрнутом режимах, сброс — ТОЛЬКО
 * ручное сворачивание («Свернуть»/Esc T082) или уход участника из
 * комнаты (прежний автосброс завершением демонстрации T074/T078
 * отменён — тайл жив и остаётся развёрнутым с камерой/аватаром);
 * автопин T078 сохраняет правило «новый screen-ключ при пустом
 * пине», ключом становится identity (повторная публикация трека —
 * переподключение демонстрирующего — считается новой демонстрацией).
 * Подпись — имя участника + видимый бейдж «демонстрирует экран» при
 * живой демонстрации (формат «Экран — имя» упразднён; доступность
 * 008 — состояние дублируется текстом); геометрия единая 16:9 —
 * object-fit contain вписывает экран, сетка/компактная полоса не
 * перестраиваются при переключениях.
 *
 * Индикатор микрофона и пометки ролей (T100, US1; прототип
 * tmp/conference-tile-mic.html, при расхождениях побеждает прототип):
 * КАЖДЫЙ тайл несёт латунный бейдж-микрофон в правом нижнем углу
 * ровно с ДВУМЯ состояниями — вкл (золотая рамка + волны) / выкл
 * (приглушение + диагональный штрих), без третьего состояния и
 * tooltip; состояние — проекция микрофонной публикации LiveKit
 * (local — isMicrophoneEnabled useLocalParticipant; remote —
 * microphone-реф publication.isMuted, зеркало T090-механики для
 * камеры; серверный мьют №49 отражается сам). Бейдж — ИНДИКАТОР, не
 * кнопка: управление своим микрофоном остаётся в MediaControls
 * (T028/T098), pointer-events: none не перехватывает клики тайла.
 * Пульс при речи — ТОЛЬКО у бейджа (speaking + mic on, свечение
 * mic-speak); текстовый индикатор «говорит» демонтирован, свечение
 * рамки/аватара не вводится; состояние бейджа дублируется
 * visually-hidden строкой (008). Пометки ролей в строке имени — из
 * participant.metadata (§3: guest → «гость», moderator →
 * «модератор», парсер ParticipantsPanel T041/T051), формат прототипа
 * «Имя · гость»; смена роли №50 (UpdateParticipant метаданных)
 * перерисовывает пометку тем же participants/tracks-стримом,
 * собственный тайл показывает свою роль тоже.
 *
 * Каркас экрана — сайдбар участников справа (T106, прототипы
 * tmp/conference-page-{moderator,participant}-{expanded,collapsed}.html,
 * при расхождениях побеждает прототип): рабочая область
 * .conference-main (flex-row) — сетка тайлов слева (растягивается,
 * внутренняя прокрутка) + правая колонка .conference-side со слотом
 * sidebar (ОБЪЕДИНЁННАЯ панель участников T107: единый список с
 * кебабом модератора и футером инструментов, скролл — только список);
 * колонка сворачивается/разворачивается иконкой-кнопкой
 * conference-panel-toggle в полосе .conference-toggle-row над
 * рабочей областью (aria-expanded/aria-controls/динамический
 * aria-label, только svg 17px без текста и рамки; свёрнутое
 * состояние — нативный [hidden], тайлы занимают всю ширину);
 * состояние — локальный стейт комнаты без localStorage (паритет пина
 * T074), в пин-оверлее T079 полоса и колонка скрываются CSS
 * body:has(.conference-room-pinned); controls (слот children) —
 * ребёнок колонки комнаты ПОД .conference-main, всегда внизу по
 * центру экрана (align-self:center; в оверлее — absolute T079).
 * Скроллбаров страницы нет: переполнение гасится внутри (сетка
 * тайлов, список участников объединённой панели T107). Гостевой паритет:
 * GuestJoinPage монтирует тот же контур (панель без инструментов
 * модерации).
 */
import {
  isTrackReference,
  RoomAudioRenderer,
  RoomContext,
  useLocalParticipant,
  useSpeakingParticipants,
  useTracks,
  VideoTrack,
} from '@livekit/components-react'
import type { TrackReference, TrackReferenceOrPlaceholder } from '@livekit/components-react'
import { DisconnectReason, Room, RoomEvent, Track } from 'livekit-client'
import type { DataPacket_Kind, RemoteParticipant } from 'livekit-client'
import { Fragment, useEffect, useId, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import type { ConferenceMode, JoinTicket } from '../api/conferences'
import { bindConferenceDisconnect } from '../hooks/useConference'
import { useDevices } from '../hooks/useDevices'
import type { ConferenceDeviceSelection } from '../hooks/useDevices'
import { ParticipantAvatar } from './ParticipantAvatar'
import { parseParticipantMarks } from './ParticipantsPanel'
import type { ParticipantMarks } from './ParticipantsPanel'
import '../conferences.css'

/** Фаза медиа-подключения комнаты — статус-строка и видимость тайлов. */
export type ConferenceRoomPhase =
  'connecting' | 'connected' | 'reconnecting' | 'failed' | 'lost' | 'closed'

/**
 * Окно реконнекта при полном разрыве (T060, FR-014, Q15=B): зеркало
 * серверного `conferences.reconnect-window` (дефолт 120 с, Q19) —
 * цепочка ретраев со свежими тикетами живёт не дольше окна от ПЕРВОЙ
 * потери связи; истечение — контролируемое завершение (lost).
 */
const DEFAULT_RECONNECT_WINDOW_MS = 120_000

/** Интервал между ретраями минта внутри окна реконнекта (T060). */
const DEFAULT_RECONNECT_RETRY_DELAY_MS = 2_000

/** Тема data-пакета предупреждения о лимите длительности (T056). */
const WARNING_TOPIC = 'conference.warning'

/** payload.type того же пакета (`{"type":"conference.warning","endsAt":…}`). */
const WARNING_TYPE = 'conference.warning'

/** Текст тоста предупреждения (FR-015, Q16=B). */
const WARNING_TOAST_TEXT = 'Конференция завершится по лимиту длительности'

/** Стартовое состояние публикации (pre-join T034; дефолт — по mode, Q3=A). */
export interface ConferenceRoomInitialMedia {
  readonly microphone: boolean
  readonly camera: boolean
}

/** Слоты стартовой публикации, недоступные из-за отказа браузера (T032, Q6=A). */
interface MediaDenied {
  readonly microphone: boolean
  readonly camera: boolean
}

const MEDIA_DENIED_NONE: MediaDenied = { microphone: false, camera: false }

export interface ConferenceRoomProps {
  /** Привязка disconnect к слоту вкладки (Q10=A) — useConference. */
  readonly conferenceId: string
  /** Действующий JoinTicket (№47): wsUrl + токен; смена токена переподключает (реконнект). */
  readonly ticket: JoinTicket
  /** Режим конференции (№45) — дефолты стартовой публикации, когда initialMedia не задан. */
  readonly mode: ConferenceMode
  /** Явное стартовое состояние из pre-join (T034); по умолчанию — дефолты mode. */
  readonly initialMedia?: ConferenceRoomInitialMedia
  /**
   * Стартовый выбор устройств pre-join (T034): применяется best-effort
   * при появлении комнаты (useDevices initial) — до первой публикации
   * треков; null/undefined — системные устройства по умолчанию.
   */
  readonly initialDevices?: ConferenceDeviceSelection | null
  /** Повторный минт №47/№55 при полном разрыве (useConference.refreshTicket, FR-014). */
  readonly refreshTicket: () => Promise<JoinTicket | null>
  /**
   * Окно реконнекта от первой потери связи до контролируемого
   * завершения (T060, conferences.reconnect-window Q19); дефолт —
   * {@link DEFAULT_RECONNECT_WINDOW_MS}; переопределяется тестами.
   */
  readonly reconnectWindowMs?: number
  /** Интервал ретраев минта внутри окна (T060); переопределяется тестами. */
  readonly reconnectRetryDelayMs?: number
  /**
   * Слот сайдбара участников (T106): правая колонка .conference-side
   * рабочей области — ОБЪЕДИНЁННАЯ панель участников (T041/T107:
   * единый список строк с кебабом действий модератора №49–№51 и
   * футером инструментов №52/№53/№48); сворачивается/разворачивается
   * кнопкой conference-panel-toggle (стейт комнаты). Гостевая точка
   * входа монтирует тот же контур (панель без инструментов модерации).
   */
  readonly sidebar?: ReactNode
  /** Слот для MediaControls (T028) — всегда внизу по центру под .conference-main (T106). */
  readonly children?: ReactNode
}

/**
 * Комната livekit-client с адаптацией качества без обрыва (FR-004):
 * simulcast (до трёх слоёв публикации — publishDefaults), dynacast
 * (издатель ставит на паузу слои, на которые никто не подписан) и
 * adaptiveStream (подписчик ставит на паузу невидимые тайлы) — при
 * деградации сети слой понижается, подключение сохраняется (quickstart
 * §3.1 шаг 4). Подписка на удалённые треки — autoSubscribe (дефолт).
 */
function createConferenceRoom(): Room {
  return new Room({
    dynacast: true,
    adaptiveStream: true,
    publishDefaults: { simulcast: true },
  })
}

/**
 * Серверные/неустранимые причины разрыва: авто-переподключение
 * бессмысленно или вредно — кик/завершение конференции не возвращают
 * (повторный вход — осознанный, по действующей ссылке, edge spec.md),
 * дубль identity и отсутствие прав — терминальные ошибки подключения.
 */
function isTerminalDisconnect(reason: DisconnectReason | undefined): boolean {
  switch (reason) {
    case DisconnectReason.PARTICIPANT_REMOVED:
    case DisconnectReason.ROOM_DELETED:
    case DisconnectReason.ROOM_CLOSED:
    case DisconnectReason.DUPLICATE_IDENTITY:
    case DisconnectReason.USER_REJECTED:
      return true
    default:
      return false
  }
}

/** Разрыв комнаты без выброса отклонённого промиса наружу (best-effort). */
function safeDisconnect(room: Room): void {
  room.disconnect().catch(() => {})
}

/**
 * Стартовая публикация: микрофон/камера по стартовому состоянию.
 * Отказ браузера в доступе (нет устройств/запрет) не рушит вход —
 * участник остаётся слушателем (FR-006; повторный запрос уже в звонке
 * — тумблеры T028, Q6=A): сбой включения помечается в возвращаемом
 * флаге слота → предупреждение; намеренно выключенный старт отказом
 * НЕ считается. Право слова/ограничения источников срезаны в grants
 * токена сервером (FR-007) — публикация молча не проходит.
 */
async function publishInitialTracks(
  room: Room,
  microphone: boolean,
  camera: boolean,
): Promise<MediaDenied> {
  const denied: { microphone: boolean; camera: boolean } = { microphone: false, camera: false }
  try {
    await room.localParticipant.setMicrophoneEnabled(microphone)
  } catch {
    denied.microphone = microphone
  }
  try {
    await room.localParticipant.setCameraEnabled(camera)
  } catch {
    denied.camera = camera
  }
  return denied
}

/** Отображаемое имя участника тайла: name тикета/метаданных, иначе identity. */
function participantLabel(name: string | undefined, identity: string): string {
  return name !== undefined && name.trim() !== '' ? name : identity
}

/** Статус-строка фазы подключения (нейтральные подписи без деталей). */
function phaseStatusText(phase: ConferenceRoomPhase): string | null {
  switch (phase) {
    case 'connecting':
      return 'Подключение к конференции…'
    case 'reconnecting':
      return 'Соединение потеряно — переподключение…'
    case 'failed':
      return 'Не удалось подключиться к медиа-серверу'
    case 'lost':
      return 'Не удалось восстановить связь с конференцией'
    case 'closed':
      return 'Вы отключены от конференции'
    default:
      return null
  }
}

/**
 * Предупреждение об отказе доступа к устройствам (T032, Q6=A): вход
 * сохранён («только слушаю», FR-006), повторный запрос — тумблерами
 * уже в звонке (T028); текст нейтральный, без раскрытия причин отказа.
 */
function mediaFailureNotice(denied: MediaDenied): string | null {
  if (denied.microphone && denied.camera) {
    return 'Нет доступа к камере и микрофону — можно включить их в звонке'
  }
  if (denied.microphone) {
    return 'Нет доступа к микрофону — можно включить его в звонке'
  }
  if (denied.camera) {
    return 'Нет доступа к камере — можно включить её в звонке'
  }
  return null
}

/**
 * Кнопка-тумблер пин-раскладки на тайле (T074): единственный явный
 * путь управления (двойной клик по тайлу — дополнение, не замена).
 * Доступность 008: aria-pressed + видимый текст «Развернуть»/
 * «Свернуть» (состояние не только цветом), нативный keyboard,
 * focus-visible (conferences.css).
 */
function TilePinButton({
  pinned,
  onToggle,
}: {
  readonly pinned: boolean
  readonly onToggle: () => void
}) {
  return (
    <button
      type="button"
      className="conference-tile-pin"
      aria-pressed={pinned}
      aria-label={pinned ? 'Свернуть' : 'Развернуть'}
      onClick={onToggle}
    >
      {pinned ? 'Свернуть' : 'Развернуть'}
    </button>
  )
}

/**
 * Индикатор микрофона тайла (T100, прототип tmp/conference-tile-mic.html):
 * латунный бейдж-микрофон в правом нижнем углу с РОВНО ДВУМЯ
 * состояниями — вкл (золотая рамка + волны звука on-частей иконки) /
 * выкл (приглушение + диагональный штрих --err); третьего состояния
 * («отключён модератором») и tooltip нет — оговорено задачей.
 * Состояние — проекция микрофонной публикации: local —
 * isMicrophoneEnabled useLocalParticipant, remote — microphone-реф
 * publication.isMuted (зеркало T090-механики для камеры; серверный
 * мьют MutePublishedTrack №49 отражается сам — isMuted). Бейдж —
 * ИНДИКАТОР, не кнопка: управление своим микрофоном остаётся в
 * MediaControls (T028/T098, без дублирования), aria-hidden +
 * pointer-events: none (conferences.css) не перехватывает клики —
 * двойной клик тайла (пин T074) жив. Пульс при речи (speaking +
 * mic on) — класс conference-tile-mic-pulse, анимируется ТОЛЬКО
 * бейдж (свечение рамки тайла/аватара не вводится); состояние
 * дублируется текстом visually-hidden строкой (конвенция 008).
 */
function TileMicBadge({
  micOn,
  speaking,
}: {
  readonly micOn: boolean
  readonly speaking: boolean
}) {
  const pulse = speaking && micOn
  return (
    <>
      <span
        className={pulse ? 'conference-tile-mic conference-tile-mic-pulse' : 'conference-tile-mic'}
        data-mic={micOn ? 'on' : 'off'}
        aria-hidden="true"
      >
        {/* инлайн-SVG винтажного микрофона из прототипа: капсюль,
            стойка, волны звука (on-части) и диагональный штрих (off) */}
        <svg viewBox="0 0 32 32" focusable="false">
          <rect x="12" y="4.5" width="8" height="11" rx="4" />
          <path d="M9 11.5v.8a7 7 0 0 0 14 0v-.8" />
          <path d="M16 19.3v3M12.6 23.2h6.8" />
          <path className="conference-tile-mic-wave" d="M5.4 13.2a4.2 4.2 0 0 0 0 5.6" />
          <path className="conference-tile-mic-wave" d="M26.6 13.2a4.2 4.2 0 0 1 0 5.6" />
          <line className="conference-tile-mic-slash" x1="6" y1="26" x2="26" y2="6" />
        </svg>
      </span>
      {/* 008: состояние не только цветом — бейдж скрыт от дерева
          доступности, текст несёт соседняя строка */}
      <span className="visually-hidden">{micOn ? 'микрофон включён' : 'микрофон выключен'}</span>
    </>
  )
}

/**
 * Пометки ролей в строке имени тайла (T100): guest → «гость»,
 * moderator → «модератор» (metadata §3, парсер ParticipantsPanel
 * T041/T051) — приглушённый курсив рядом с именем, формат прототипа
 * «Имя · гость»; обе пометки рядом (гость-модератор), обычный
 * участник — без пометок. Живость: смена роли №50 (UpdateParticipant
 * метаданных) приезжает тем же participants/tracks-стримом.
 */
function TileNameMarks({ marks }: { readonly marks: ParticipantMarks }) {
  const notes: string[] = []
  if (marks.guest) {
    notes.push('гость')
  }
  if (marks.role === 'moderator') {
    notes.push('модератор')
  }
  if (notes.length === 0) {
    return null
  }
  return (
    <>
      {notes.map((note) => (
        <Fragment key={note}>
          {' · '}
          <span className="conference-tile-mark">{note}</span>
        </Fragment>
      ))}
    </>
  )
}

/**
 * Слитный тайл участника (T084): экран, камера и аватар-заглушка
 * одного участника — ОДИН элемент с React-ключом identity. Содержимое
 * — производное состояния модели (приоритет screen >
 * published-и-незамученная camera > placeholder, T090): published-экран
 * / незамученная камера — VideoTrack (ОДИН video-элемент на тайл:
 * смена trackRef перевешивает attach того же элемента, DOM-узел
 * переиспользуется — переключения без размонтирования тайла и
 * перестройки сетки), замученная (выключенная) камера и placeholder
 * (камера никогда не публиковалась/недоступна — собственный тайл тоже)
 * — своя заглушка вместо дефолтной ParticipantTile: ui/Avatar
 * (ParticipantAvatar, «как в контактах» T073) + имя.
 * Живая демонстрация маркируется текстовым бейджем «демонстрирует
 * экран» рядом с именем (доступность 008 — состояние дублируется
 * текстом; формат «Экран — имя» упразднён).
 *
 * Индикатор микрофона (T100): КАЖДЫЙ тайл (и видео-вариант, и
 * idle-заглушка) несёт бейдж TileMicBadge в правом нижнем углу —
 * имя участника вынесено в собственный спан (точные текст-селекторы
 * e2e при пометках ролей в той же строке), «Экран»-тайла как
 * отдельного источника нет — источник бейджа участник с микрофоном,
 * слитый тайл решает сам (T084: у одного участника один тайл);
 * текстовый индикатор речи «говорит» демонтирован — речь отражает
 * пульс бейджа.
 *
 * Пин-раскладка (T074, ключ identity — T084): pinned — тайл
 * развёрнут большим на всю медиа-зону (conference-tile-pinned;
 * аватар заглушки крупнее), onTogglePin — тумблер кнопкой/двойным
 * кликом.
 */
function ConferenceTile({
  model,
  speaking,
  pinned = false,
  onTogglePin,
}: {
  readonly model: ParticipantTileModel
  readonly speaking: boolean
  readonly pinned?: boolean
  readonly onTogglePin: () => void
}) {
  const label = participantLabel(model.participant.name, model.participant.identity)
  const marks = parseParticipantMarks(model.participant.metadata)
  // Muted-камера = нет видео (T090): выключение камеры (тумблер
  // MediaControls / модерация MutePublishedTrack) — это mute трека,
  // НЕ unpublish (с публикации LiveKit снимает только демонстрация
  // экрана), published-but-muted реф рендерил бы чёрный кадр вместо
  // аватара — камера считается видеоисточником только published И
  // незамученной (publication.isMuted одинаков для local/remote —
  // зеркало useIsMuted из @livekit/components-react; реактивность —
  // useTracks перевыпускает рефы на TrackMuted/TrackUnmuted).
  const cameraPublished =
    model.cameraRef !== undefined &&
    isTrackReference(model.cameraRef) &&
    model.cameraRef.publication.isMuted !== true
      ? model.cameraRef
      : undefined
  const videoRef = model.screenRef ?? cameraPublished
  const tileClass = pinned ? 'conference-tile conference-tile-pinned' : 'conference-tile'
  if (videoRef !== undefined) {
    return (
      <figure className={tileClass} onDoubleClick={onTogglePin}>
        <VideoTrack trackRef={videoRef} />
        <TilePinButton pinned={pinned} onToggle={onTogglePin} />
        <figcaption className="conference-tile-name">
          <span className="conference-tile-username">{label}</span>
          <TileNameMarks marks={marks} />
          {model.screenRef !== undefined && (
            <span className="conference-tile-sharing">демонстрирует экран</span>
          )}
        </figcaption>
        <TileMicBadge micOn={model.micOn} speaking={speaking} />
      </figure>
    )
  }
  return (
    <figure
      className={
        pinned ? `${tileClass} conference-tile-idle` : 'conference-tile conference-tile-idle'
      }
      onDoubleClick={onTogglePin}
    >
      <ParticipantAvatar
        participant={model.participant}
        size={pinned ? 144 : 96}
        className="conference-tile-avatar"
      />
      <TilePinButton pinned={pinned} onToggle={onTogglePin} />
      <figcaption className="conference-tile-name">
        <span className="conference-tile-username">{label}</span>
        <TileNameMarks marks={marks} />
      </figcaption>
      <TileMicBadge micOn={model.micOn} speaking={speaking} />
    </figure>
  )
}

/**
 * View-model слитого тайла участника (T084): слияние camera- и
 * screen-рефов useTracks по identity с приоритетом screen >
 * published-и-незамученная camera > placeholder (T090: выключение
 * камеры — mute трека, НЕ unpublish — замученный published-реф
 * видеоисточником НЕ считается). T077+T085 гарантируют
 * взаимоисключительность для собственного пользователя симметрично
 * (старт демонстрации гасит камеру, включение камеры останавливает
 * демонстрацию); чужой клиент, публикующий оба трека,
 * детерминированно виден экраном — камера вытесняется до завершения
 * демонстрации (в т.ч. замученная), второй тайл НЕ рождается.
 */
interface ParticipantTileModel {
  /** Ключ тайла и пина (T084): identity участника, НЕ identity:source. */
  readonly identity: string
  readonly participant: TrackReferenceOrPlaceholder['participant']
  /** Живая демонстрация экрана участника (published-реф) или undefined. */
  readonly screenRef: TrackReference | undefined
  /** Camera-реф участника (published или placeholder) или undefined. */
  readonly cameraRef: TrackReferenceOrPlaceholder | undefined
  /**
   * Микрофон включён (T100): local — isMicrophoneEnabled
   * useLocalParticipant, remote — microphone-реф незамучен
   * (publication.isMuted; нет публикации = выкл). Индикатор, не
   * управление — бейдж тайла.
   */
  readonly micOn: boolean
}

/**
 * Слияние camera- и screen-рефов useTracks в слитые тайлы участников
 * (T084) с микрофонным состоянием (T100) — чистая проекция живых
 * рефов без хуков. Приоритет screen > camera > placeholder; порядок
 * стабильный — камеры по порядку useTracks, затем экраны участников
 * без camera-рефа. Микрофон тайла: local — isMicrophoneEnabled (свой
 * тумблер T028/серверный мьют отражаются сам), remote — микрофонная
 * публикация (нет/замучена → выкл; мьют тумблером цели снимает
 * публикацию, серверный мьют №49 оставляет её замученной — оба пути
 * дают «выкл», нет публикации = выкл).
 */
function buildParticipantTiles(
  cameraTiles: readonly TrackReferenceOrPlaceholder[],
  screenTiles: readonly TrackReference[],
  micTiles: readonly TrackReference[],
  localIdentity: string,
  localMicOn: boolean,
): ParticipantTileModel[] {
  const micOnByIdentity = new Map<string, boolean>()
  for (const ref of micTiles) {
    micOnByIdentity.set(ref.participant.identity, ref.publication.isMuted !== true)
  }
  const screenByIdentity = new Map<string, TrackReference>()
  for (const ref of screenTiles) {
    screenByIdentity.set(ref.participant.identity, ref)
  }
  const micOn = (identity: string): boolean =>
    identity === localIdentity ? localMicOn : (micOnByIdentity.get(identity) ?? false)
  const tiles: ParticipantTileModel[] = cameraTiles.map((ref) => {
    const identity = ref.participant.identity
    return {
      identity,
      participant: ref.participant,
      cameraRef: ref,
      screenRef: screenByIdentity.get(identity),
      micOn: micOn(identity),
    }
  })
  const cameraIdentities = new Set(cameraTiles.map((ref) => ref.participant.identity))
  for (const [identity, screenRef] of screenByIdentity) {
    if (!cameraIdentities.has(identity)) {
      tiles.push({
        identity,
        participant: screenRef.participant,
        cameraRef: undefined,
        screenRef,
        micOn: micOn(identity),
      })
    }
  }
  return tiles
}

/**
 * Автопин демонстрации экрана (T078, ключ identity — T084): появление
 * НОВОГО screen-ключа (переход «не было → появился», включая
 * собственную демонстрацию) при ПУСТОМ пине разворачивает слитый
 * тайл демонстрирующего на весь экран без ручного клика — у всех
 * участников. Детерминированное правило «только при пустом пине»:
 * ручной пин зрителя стартом демонстрации не перебивается, вторая
 * одновременная демонстрация не переключает уже автозакреплённую
 * первую, ручное сворачивание автопина при живой демонстрации
 * возвращает сетку БЕЗ повторного автопина (решение принимается на
 * появление нового ключа, не на инвариант «пустой пин + живые экраны»
 * — иначе цикл); повторная публикация трека (переподключение
 * демонстрирующего: ключ ушёл из живых и вернулся) считается новой
 * демонстрацией. Решение — вывод в фазе рендера (derived state,
 * React): атомарно с рендером, заметившим новый ref, без гонки
 * эффекта с ручным кликом на первом коммите. Замеченные демонстрации
 * предыдущего рендера — локальный стейт хука: набор заменяется живыми
 * ключами, ушедший ключ (завершение/переподключение) снова считается
 * новым при возвращении.
 */
function useScreenShareAutoPin(
  mediaVisible: boolean,
  screenIdentities: readonly string[],
  livePinTarget: boolean,
  autoPin: (identity: string) => void,
): void {
  const [seenScreenKeys, setSeenScreenKeys] = useState<ReadonlySet<string>>(() => new Set<string>())
  const appearedScreenIdentity = screenIdentities.find((identity) => !seenScreenKeys.has(identity))
  if (
    mediaVisible &&
    (appearedScreenIdentity !== undefined || seenScreenKeys.size !== screenIdentities.length)
  ) {
    if (appearedScreenIdentity !== undefined && !livePinTarget) {
      autoPin(appearedScreenIdentity)
    }
    setSeenScreenKeys(new Set(screenIdentities))
  }
}

/**
 * Медиа-слой + корневой контур комнаты (T079 — пин-состояние поднято
 * сюда из медиа-слоя; внутри RoomContext — хукам useTracks/
 * useSpeakingParticipants нужна живая комната): аудио всех удалённых
 * участников — RoomAudioRenderer (микрофоны и звук демонстраций
 * экрана), видео — ЕДИНАЯ сетка слитых тайлов участников (T084:
 * ConferenceTile — экран/камера/аватар в одном элементе по identity;
 * широкой секции демонстраций больше нет). adaptiveStream держит
 * паузу невидимых тайлов (FR-004). Слот children (MediaControls/
 * панели US3/US4) остаётся в DOM под RoomContext — переносить нельзя
 * (хуки livekit), геометрия пин-оверлея — CSS.
 *
 * Порядок рендера детей колонки комнаты (T114): полоса кнопки панели
 * (T106) → рабочая область .conference-main (тайлы + сайдбар) →
 * уведомления комнаты (статус-строка фаз T027/T060, media-
 * предупреждение T032, тост conference.warning T060) → слот controls
 * (children, всегда внизу по центру): уведомления живут в полосе
 * ПОД рабочей областью и НАД панелью управления — flex-поток колонки
 * сам размещает плашки (gap колонки), рабочая область не отжимается
 * сверху; фазы без main (connecting/failed/lost/closed) — уведомления
 * остаются над всегда смонтированным controls; ранняя оболочка до
 * создания комнаты — только статус (см. ConferenceRoom ниже); в
 * пин-оверлее T079 DOM-позиция уведомлений не важна — их
 * позиционируют absolute-правила CSS.
 *
 * Пин-раскладка (T074, ключ identity — T084): локальное состояние
 * закреплённого тайла (identity участника) — закреплённый рендерится
 * большим, остальные — компактной полосой; повторный пин другого
 * тайла — переключение (закреплён всегда ≤1), закреплённого —
 * сворачивание. Пин-цель выводится из живых тайлов: выход участника
 * из комнаты — цель исчезает → обычная сетка (состояние комнаты, без
 * localStorage — на размонтировании звонка исчезает); переключения
 * камера↔экран пин НЕ сбрасывают — тайл жив (T084). Треки подписаны
 * (onlySubscribed: false) — adaptiveStream (FR-004) сам поднимает
 * качество ставшему большим тайлу, ничего дособирать не нужно.
 *
 * Полноэкранный пин-оверлей (T079): живой пин → маркер-класс
 * conference-room-pinned на корневом div комнаты — CSS (conferences.css)
 * разворачивает комнату на весь вьюпорт браузера без скроллбаров
 * (position: fixed; скрытие служебных панелей страницы по body:has(…);
 * MediaControls — absolute снизу по центру поверх тайла; статус-строки
 * и тост — absolute-оверлеи сверху). Снятие пина (сворачивание/уход
 * цели) снимает маркер тем же рендером, что возвращает обычную сетку.
 * Обе точки входа (член/гость) получают одинаковую геометрию одним
 * маркером. Fullscreen API — вне scope (паритет T074/T075).
 *
 * Автопин демонстрации экрана (T078, ключ identity — T084):
 * появление НОВОГО screen-ключа (переход «не было → появился»,
 * включая собственную демонстрацию) при ПУСТОМ пине разворачивает
 * слитый тайл демонстрирующего на весь экран без ручного клика — у
 * всех участников. Детерминированное правило «только при пустом
 * пине»: ручной пин зрителя стартом демонстрации не перебивается,
 * вторая одновременная демонстрация не переключает уже
 * автозакреплённую первую, ручное сворачивание автопина при живой
 * демонстрации возвращает сетку БЕЗ повторного автопина (решение
 * принимается на появление нового ключа, не на инвариант «пустой пин
 * + живые экраны» — иначе цикл); повторная публикация трека
 * (переподключение демонстрирующего: ключ ушёл из живых и вернулся)
 * считается новой демонстрацией. Решение — вывод в фазе рендера
 * (derived state, React): атомарно с рендером, заметившим новый ref,
 * без гонки эффекта с ручным кликом на первом коммите.
 */
function ConferenceRoomLive({
  conferenceId,
  phase,
  mediaNotice,
  warningVisible,
  onWarningDismiss,
  sidebar,
  children,
}: {
  readonly conferenceId: string
  readonly phase: ConferenceRoomPhase
  /** Текст предупреждения об отказе устройств (T032) — null, если отказа нет. */
  readonly mediaNotice: string | null
  /** Тост conference.warning показан (T060 ← T056). */
  readonly warningVisible: boolean
  readonly onWarningDismiss: () => void
  /** Слот сайдбара участников (T106) — правая колонка рабочей области. */
  readonly sidebar?: ReactNode
  readonly children?: ReactNode
}) {
  const cameraTiles = useTracks([{ source: Track.Source.Camera, withPlaceholder: true }], {
    onlySubscribed: false,
  })
  const screenTiles = useTracks([Track.Source.ScreenShare], { onlySubscribed: false })
  // Микрофонные публикации (T100): состояние бейджа remote-тайлов —
  // publication.isMuted (зеркало T090 для камеры: мьют тумблером цели
  // снимает публикацию, серверный мьют №49 оставляет её замученной —
  // оба пути дают «выкл»; нет публикации = выкл). Local-состояние —
  // isMicrophoneEnabled ниже (канонический источник собственного
  // микрофона).
  const micTiles = useTracks([Track.Source.Microphone], { onlySubscribed: false })
  const { localParticipant, isMicrophoneEnabled } = useLocalParticipant()
  const speakers = useSpeakingParticipants()
  const speakerIdentities = new Set(speakers.map((participant) => participant.identity))
  const [pinnedTileKey, setPinnedTileKey] = useState<string | null>(null)
  // Сайдбар участников (T106): развёрнут по умолчанию, сворачивается
  // кнопкой; локальный стейт комнаты без localStorage (паритет пина
  // T074) — на размонтировании звонка исчезает. id колонки — цель
  // aria-controls кнопки.
  const [participantsOpen, setParticipantsOpen] = useState(true)
  const participantsPanelId = useId()
  // Медиа-тайлы рендерятся только в живых фазах (как монтаж медиа-слоя
  // до T079): маркер оверлея и автопин следуют тому же окну.
  const mediaVisible = phase === 'connected' || phase === 'reconnecting'
  // Слияние camera+screen в один тайл по участнику (T084) и микрофон
  // тайлов (T100) — чистая проекция живых рефов buildParticipantTiles:
  // приоритет screen > camera > placeholder, порядок стабильный —
  // камеры по порядку useTracks, затем экраны участников без
  // camera-рефа. React-ключ — identity: смена набора рефов
  // (переключения камеры/экрана/заглушки) переиспользует те же
  // DOM-узлы, сетка не перестраивается, второй тайл участника не
  // рождается.
  const tiles = buildParticipantTiles(
    cameraTiles,
    screenTiles,
    micTiles,
    localParticipant.identity,
    isMicrophoneEnabled,
  )
  // Пин-цель из живых тайлов по identity (undefined — цель исчезла:
  // выход участника из комнаты → обычная сетка; переключения
  // камера↔экран цель НЕ роняют — тайл жив, T084).
  const pinnedTile =
    pinnedTileKey === null ? undefined : tiles.find((tile) => tile.identity === pinnedTileKey)
  // Живой пин (цель существует): пустой пин для автопина T078 — именно
  // отсутствие развёрнутого тайла (в т.ч. «висячий» ключ после ухода
  // закреплённого участника), а не только null в состоянии.
  const livePinTarget = pinnedTile !== undefined
  // Маркер оверлея — ровно когда развёрнутый тайл реально рендерится
  // (T079): терминальные/служебные фазы маркер не поднимают.
  const roomPinned = mediaVisible && livePinTarget
  const togglePin = (identity: string): void => {
    setPinnedTileKey((current) => (current === identity ? null : identity))
  }
  // Автопин демонстраций экрана (T078) — useScreenShareAutoPin ниже:
  // появление нового screen-ключа при пустом пине применяется ровно
  // на первом рендере появления (derived state, setState в фазе
  // рендера своего компонента — React перерендерит до коммита;
  // последующие ручные клики видят уже применённый автопин).
  useScreenShareAutoPin(
    mediaVisible,
    screenTiles.map((ref) => ref.participant.identity),
    livePinTarget,
    setPinnedTileKey,
  )
  // Клавиатурный выход из пин-оверлея (T082, доступность 008): Esc при
  // живом пине сворачивает закреплённый тайл — та же семантика, что
  // кнопка «Свернуть» (setPinnedTileKey(null), ≤1 закреплён, состояние
  // комнаты — без localStorage). Слушатель keydown на window живёт
  // ТОЛЬКО в пин-состоянии (roomPinned) и снимается эффектом при
  // сворачивании/уходе цели/размонтировании — при пустом пине Esc —
  // no-op, обработчика нет; прочие клавиши игнорируются; ввод в полях
  // (input/textarea/contenteditable) не перехватывается. Esc НЕ
  // завершает конференцию и НЕ покидает комнату; повторный автопин
  // T078 невозможен (решение на появление НОВОГО screen-ключа),
  // повторный ручной пин работает (паритет детерминированных правил).
  useEffect(() => {
    if (!roomPinned) {
      return
    }
    const collapsePinnedTile = (event: KeyboardEvent): void => {
      if (event.key !== 'Escape') {
        return
      }
      const target = event.target
      if (
        target instanceof HTMLElement &&
        (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable)
      ) {
        return
      }
      setPinnedTileKey(null)
    }
    window.addEventListener('keydown', collapsePinnedTile)
    return () => {
      window.removeEventListener('keydown', collapsePinnedTile)
    }
  }, [roomPinned])
  const statusText = phaseStatusText(phase)
  const roomClass = roomPinned ? 'conference-room conference-room-pinned' : 'conference-room'
  const restTiles = pinnedTile === undefined ? tiles : tiles.filter((tile) => tile !== pinnedTile)
  return (
    <div className={roomClass} data-conference-room={conferenceId}>
      {mediaVisible && (
        <>
          <RoomAudioRenderer />
          {sidebar !== undefined && (
            // Полоса места над рабочей областью (T106): иконка-кнопка
            // справа, прижата к рабочей области (компенсация gap — CSS);
            // доступность 008 — aria-expanded/aria-controls/динамический
            // aria-label; в пин-оверлее T079 полоса скрывается CSS
            // body:has(.conference-room-pinned)
            <div className="conference-toggle-row">
              <button
                type="button"
                className="conference-panel-toggle"
                aria-expanded={participantsOpen}
                aria-controls={participantsPanelId}
                aria-label={
                  participantsOpen ? 'Свернуть панель участников' : 'Развернуть панель участников'
                }
                onClick={() => {
                  setParticipantsOpen((open) => !open)
                }}
              >
                {/* только svg-иконка сайдбара с шевроном 17px — без
                    текста, рамки, фона и теней (прототип T106); шеврон
                    ‹ развёрнута / › свёрнута — поворот CSS-правилом */}
                <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
                  <rect x="3.5" y="5" width="17" height="14" rx="2" />
                  <path d="M14.5 5v14" />
                  <path className="conference-panel-chevron" d="M10.5 9.5 8 12l2.5 2.5" />
                </svg>
              </button>
            </div>
          )}
          {/* Рабочая область на всё окно (T106): тайлы слева (растягиваются,
              внутренняя прокрутка) + правая колонка сайдбара; пин-тайл
              T074/T079 — первый ребёнок области (в оверлее остальное
              скрыто CSS); controls (children) — ПОД областью в колонке
              комнаты, всегда внизу по центру экрана */}
          <div className="conference-main">
            {pinnedTile !== undefined && (
              <ConferenceTile
                model={pinnedTile}
                pinned
                speaking={speakerIdentities.has(pinnedTile.identity)}
                onTogglePin={() => togglePin(pinnedTile.identity)}
              />
            )}
            <section
              className={
                pinnedTile === undefined
                  ? 'conference-tiles'
                  : 'conference-tiles conference-tiles-compact'
              }
              aria-label="Участники конференции"
            >
              {restTiles.map((tile) => (
                <ConferenceTile
                  key={tile.identity}
                  model={tile}
                  speaking={speakerIdentities.has(tile.identity)}
                  onTogglePin={() => togglePin(tile.identity)}
                />
              ))}
            </section>
            {sidebar !== undefined && (
              // Правая колонка сайдбара (T106): свёрнутое состояние —
              // нативный [hidden] (CSS-гвард гасит display:flex), тайлы
              // занимают всю ширину; DOM слота остаётся смонтированным
              <aside
                className="conference-side"
                id={participantsPanelId}
                hidden={!participantsOpen}
              >
                {sidebar}
              </aside>
            )}
          </div>
        </>
      )}
      {/* Уведомления комнаты (T114) — дети колонки ПОД рабочей областью
          .conference-main (когда медиа-слой смонтирован) и НАД слотом
          controls: flex-поток ставит плашки в полосу между ними (зазор —
          gap колонки), рабочая область не отжимается сверху; фазы без
          main (connecting/failed/lost/closed) — те же блоки остаются над
          всегда смонтированным controls («под main, если есть»); в
          пин-оверлее T079 DOM-позиция не важна — absolute-правила CSS.
          Тексты/условия показа — прежние (T027/T032/T060). */}
      {statusText !== null && <output className="conference-room-status">{statusText}</output>}
      {mediaNotice !== null && <output className="conference-room-media">{mediaNotice}</output>}
      {warningVisible && (
        <output className="conference-warning-toast" data-conference-warning>
          <span>{WARNING_TOAST_TEXT}</span>
          <button type="button" onClick={onWarningDismiss}>
            Закрыть
          </button>
        </output>
      )}
      {children}
    </div>
  )
}

export default function ConferenceRoom({
  conferenceId,
  ticket,
  mode,
  initialMedia,
  initialDevices,
  refreshTicket,
  reconnectWindowMs = DEFAULT_RECONNECT_WINDOW_MS,
  reconnectRetryDelayMs = DEFAULT_RECONNECT_RETRY_DELAY_MS,
  sidebar,
  children,
}: ConferenceRoomProps) {
  /** Комната текущего жизненного цикла тикета (создаётся эффектом, T027). */
  const [room, setRoom] = useState<Room | null>(null)
  const [phase, setPhase] = useState<ConferenceRoomPhase>('connecting')
  /** Слоты стартовой публикации, недоступные из-за отказа браузера (T032). */
  const [mediaDenied, setMediaDenied] = useState<MediaDenied>(MEDIA_DENIED_NONE)
  /** Тост conference.warning (T056 → T060): показан до закрытия/завершения. */
  const [warningVisible, setWarningVisible] = useState(false)
  /** Разрыв инициирован выходом (слот вкладки/размонтирование) — без авто-возврата. */
  const intentionalRef = useRef(false)
  /** Успешное подключение уже было — реконнект с новым тикетом допустим (FR-014). */
  const everConnectedRef = useRef(false)
  /**
   * Дедлайн окна реконнекта (T060): timestamp от ПЕРВОЙ полной потери
   * связи цепочки; null — окно закрыто (подключение восстановлено /
   * терминальный выход). Переживает смену комнаты (смена тикета) —
   * серийные разрывы держат одно окно, при восстановлении
   * сбрасывается и следующая потеря открывает заново.
   */
  const reconnectDeadlineRef = useRef<number | null>(null)
  /** Таймер ретрая минта внутри окна (гасится cleanup'ом прогона). */
  const retryTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const refreshTicketRef = useRef(refreshTicket)
  refreshTicketRef.current = refreshTicket

  const startMicrophone = initialMedia?.microphone ?? true
  const startCamera = initialMedia?.camera ?? mode === 'video'

  // Устройства — единая точка useDevices (T031): стартовый выбор pre-join
  // (T034) применяется best-effort при появлении комнаты — switchActive-
  // Device до connect настраивает capture-дефолты, публикация стартует
  // выбранными устройствами; синхронизация выбора — ActiveDeviceChanged.
  useDevices({ room, initial: initialDevices ?? undefined })

  // Жизненный цикл тикета = жизненный цикл комнаты: СВЕЖАЯ Room на каждый
  // прогон эффекта (паттерн useLiveKitRoom — см. комментарий модуля),
  // слушатели + подключение + привязка разрыва к слоту вкладки (Q10=A):
  // leaveActiveConference (leave/pagehide/join другой конференции)
  // вызывает bound-disconnect — флаг intentional гасит авто-реконнект;
  // cleanup (смена тикета/размонтирование/StrictMode-перемонтаж) рвёт
  // подключение ЭТОЙ комнаты, повторный прогон строит свежую.
  useEffect(() => {
    intentionalRef.current = false
    let disposed = false
    /**
     * Разрыв этой комнаты уже запустил цепочку реконнекта (T060):
     * повторные Disconnected мёртвого экземпляра — дубликаты, новый
     * минт не инициируют (свежий тикет уже получен/запланирован).
     */
    let chainTriggered = false
    const active = createConferenceRoom()
    setMediaDenied(MEDIA_DENIED_NONE)

    /**
     * Окно истекло — контролируемое завершение (T060, Q15=B):
     * понятное сообщение, окно закрывается, ретраи прекращаются.
     */
    const expireReconnectWindow = (): void => {
      reconnectDeadlineRef.current = null
      if (retryTimerRef.current !== null) {
        clearTimeout(retryTimerRef.current)
        retryTimerRef.current = null
      }
      setPhase('lost')
    }
    /** Остаток окна: null — окно закрыто (не реконнект) или ещё активно. */
    const reconnectExpired = (): boolean => {
      const deadline = reconnectDeadlineRef.current
      return deadline !== null && Date.now() >= deadline
    }
    const scheduleReconnectRetry = (): void => {
      if (disposed || intentionalRef.current) {
        return
      }
      if (reconnectExpired()) {
        expireReconnectWindow()
        return
      }
      if (retryTimerRef.current !== null) {
        return
      }
      retryTimerRef.current = setTimeout(() => {
        retryTimerRef.current = null
        attemptReconnect()
      }, reconnectRetryDelayMs)
    }
    /**
     * Попытка реконнекта (T060, FR-014): свежий тикет повторным
     * №47/№55 — истёкший TTL не годится (900 с < 4 ч). Успех с НОВЫМ
     * токеном — эстафета родителю (смена ticket.token пересоздаёт
     * комнату, цепочка продолжится прогоном свежего тикета); null
     * (сбой/устаревание/конференция завершена) и прочие сбои —
     * ретрай, пока окно живо.
     */
    const attemptReconnect = (): void => {
      if (disposed || intentionalRef.current) {
        return
      }
      if (reconnectExpired()) {
        expireReconnectWindow()
        return
      }
      void refreshTicketRef
        .current()
        .then((fresh) => {
          if (disposed || intentionalRef.current) {
            return
          }
          if (fresh !== null && fresh.token !== ticket.token) {
            chainTriggered = true
            return
          }
          scheduleReconnectRetry()
        })
        .catch(() => {
          if (!disposed && !intentionalRef.current) {
            scheduleReconnectRetry()
          }
        })
    }

    const handleReconnecting = () => {
      if (!disposed) {
        setPhase('reconnecting')
      }
    }
    const handleReconnected = () => {
      if (!disposed) {
        // Транзиентный восстановился сам — окно не нужно (T060).
        reconnectDeadlineRef.current = null
        setPhase('connected')
      }
    }
    /**
     * Предупреждение о лимите длительности (T056 → T060, Q16=B):
     * data-пакет topic `conference.warning` (серверный SendData —
     * единственный канал гостям без SSE) → тост; чужие темы и битый
     * payload игнорируются нейтрально.
     */
    const handleDataReceived = (
      payload: Uint8Array,
      _participant?: RemoteParticipant,
      _kind?: DataPacket_Kind,
      topic?: string,
    ): void => {
      if (disposed || topic !== WARNING_TOPIC) {
        return
      }
      try {
        const parsed: unknown = JSON.parse(new TextDecoder().decode(payload))
        if (
          parsed !== null &&
          typeof parsed === 'object' &&
          (parsed as { type?: unknown }).type === WARNING_TYPE
        ) {
          setWarningVisible(true)
        }
      } catch {
        // битый payload — без реакции (нейтрально)
      }
    }
    /**
     * Повторный запрос доступа уже в звонке (T032, Q6=A): успешное
     * включение слота тумблером MediaControls (T028) публикует трек —
     * LocalTrackPublished снимает свою часть предупреждения об отказе.
     */
    const handleLocalTrackPublished = (publication: { source: Track.Source }) => {
      if (disposed) {
        return
      }
      if (publication.source === Track.Source.Microphone) {
        setMediaDenied((current) =>
          current.microphone ? { ...current, microphone: false } : current,
        )
      } else if (publication.source === Track.Source.Camera) {
        setMediaDenied((current) => (current.camera ? { ...current, camera: false } : current))
      }
    }
    const handleDisconnected = (reason?: DisconnectReason) => {
      if (disposed) {
        return
      }
      if (intentionalRef.current || reason === DisconnectReason.CLIENT_INITIATED) {
        return
      }
      if (isTerminalDisconnect(reason)) {
        reconnectDeadlineRef.current = null
        setPhase('closed')
        return
      }
      if (!everConnectedRef.current) {
        setPhase('failed')
        return
      }
      // Дубликаты разрыва той же мёртвой комнаты — уже в цепочке (T060).
      if (chainTriggered) {
        return
      }
      chainTriggered = true
      // Окно — от ПЕРВОЙ потери цепочки (серийные разрывы держат одно).
      reconnectDeadlineRef.current ??= Date.now() + reconnectWindowMs
      setPhase('reconnecting')
      attemptReconnect()
    }
    active.on(RoomEvent.Reconnecting, handleReconnecting)
    active.on(RoomEvent.Reconnected, handleReconnected)
    active.on(RoomEvent.Disconnected, handleDisconnected)
    active.on(RoomEvent.DataReceived, handleDataReceived)
    active.on(RoomEvent.LocalTrackPublished, handleLocalTrackPublished)
    bindConferenceDisconnect(conferenceId, () => {
      intentionalRef.current = true
      reconnectDeadlineRef.current = null
      safeDisconnect(active)
    })
    setRoom(active)
    setPhase('connecting')
    void (async () => {
      try {
        await active.connect(ticket.wsUrl, ticket.token)
        if (disposed) {
          return
        }
        everConnectedRef.current = true
        // Подключение восстановлено (в т.ч. цепочкой реконнекта) —
        // окно закрыто, следующая потеря откроет заново (T060).
        reconnectDeadlineRef.current = null
        setPhase('connected')
        const denied = await publishInitialTracks(active, startMicrophone, startCamera)
        if (!disposed) {
          setMediaDenied(denied)
        }
      } catch {
        if (disposed) {
          return
        }
        if (everConnectedRef.current && reconnectDeadlineRef.current !== null) {
          // Свежий тикет цепочки не подключился — ретрай до конца окна.
          setPhase('reconnecting')
          scheduleReconnectRetry()
          return
        }
        setPhase('failed')
      }
    })()
    return () => {
      disposed = true
      intentionalRef.current = true
      if (retryTimerRef.current !== null) {
        clearTimeout(retryTimerRef.current)
        retryTimerRef.current = null
      }
      active.removeAllListeners()
      safeDisconnect(active)
    }
  }, [
    conferenceId,
    ticket.wsUrl,
    ticket.token,
    startMicrophone,
    startCamera,
    reconnectWindowMs,
    reconnectRetryDelayMs,
  ])

  const statusText = phaseStatusText(phase)
  const mediaNotice = mediaFailureNotice(mediaDenied)

  // До создания комнаты (первый рендер до эффекта) — только статус-оболочка:
  // children (MediaControls и панели US3/US4) живут ВНУТРИ RoomContext —
  // хуки @livekit/components-react без комнаты нерендерибельны.
  if (room === null) {
    return (
      <div className="conference-room" data-conference-room={conferenceId}>
        {statusText !== null && <output className="conference-room-status">{statusText}</output>}
      </div>
    )
  }
  // Живой контур (T079): под RoomContext (хукам тайлов нужна живая
  // комната) — корневой div + медиа-слой + children; пин-состояние и
  // маркер полноэкранного оверлея — в контуре.
  return (
    <RoomContext.Provider value={room}>
      <ConferenceRoomLive
        conferenceId={conferenceId}
        phase={phase}
        mediaNotice={mediaNotice}
        warningVisible={warningVisible}
        onWarningDismiss={() => {
          setWarningVisible(false)
        }}
        sidebar={sidebar}
      >
        {children}
      </ConferenceRoomLive>
    </RoomContext.Provider>
  )
}
