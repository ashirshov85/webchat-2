/**
 * The open dialog header (feature 008, US1 T021 → US3 T043; FR-016,
 * FR-017, data-model 2.3): the `.chat-head` block of the prototype
 * specs/008-chat-window-styling/design/chats.html §7 — `.head-av`
 * avatar + `.chat-title` (`.chat-name` + `.status-row`) + the
 * right-side controls, styled by chat-header.css (FR-001: a verbatim
 * projection; the prototype wins on conflicts). The name keeps the
 * 004 hook `dialog-title` on its h2 (research §C, FR-034).
 *
 * The status row is the FULL US3 projection (T043):
 *  * direct — the prototype presence-лампа `.lamp` + `.status-txt`
 *    label driven by the 007 presenceStore; 008a US3 (T048, ui-behavior
 *    §3, FR-011) widens the label through `usePresenceEntry(peerId)`
 *    (T047: status + lastSeenAt?): online — «В сети» (без изменений
 *    007), offline с раскрытым `lastSeenAt` — «Был в сети — {время}»
 *    (`lastSeenFormat` T046: локаль ru-RU, пояс наблюдателя), offline
 *    без поля — единый нейтральный фолбэк «Был в сети — давно» (нет
 *    данных/инкогнито/блок-пара неотличимы, SC-002). The T040
 *    contract holds: `.off` ONLY for a true store offline — a missing
 *    №36 snapshot and №36 `unknown` never yield a false «офлайн»);
 *    bug 12 (T090): `unknown` renders
 *    NO indicator at all — the empty `.status-row` keeps the height
 *    (chat-header.css min-height) until №36 converges; the blocker-side
 *    «заблокирован» mark follows (FR-020);
 *  * group — «N участников» with the prototype pluralRu; hover/focus
 *    of the status row opens the `.members-tip` roster (FR-017):
 *    «Вы» first, then the live №28 members minus me — the split role
 *    marks of bug 18 (T096): owner rows carry «владелец», admin rows
 *    «админ», member rows no mark; the «Вы» row follows the same
 *    scheme by `myRole` (the 006 role model is finer than the
 *    prototype demo data — its single «администратор» mark). The tip
 *    is portaled to document.body — the prototype's
 *    body-level `#membersTip` (fixed, z-56, pointer-events: none) —
 *    positioned by the prototype clamp formulas against the status
 *    row rect and closed by mouseleave/blur/Esc; Esc is captured as
 *    the top layer of data-model 3.1 so it never falls through to a
 *    modal/drawer below. While №28 is not live (the №12 basis only,
 *    `members === null`) the row is no tip source — not focusable,
 *    hover is a no-op. The prototype's own-row presence dot
 *    (mePresenceDot) is not carried: data-model 2.3 derives the tip
 *    from members + myRole only.
 *
 * US4 (T054): the «шестерёнка» ChatGearMenu (ui-behavior §4) — direct:
 * №21 add / №23-24 block toggle / №14 delete (подтверждения — у
 * страницы через форму confirm оболочки T034); group: the №28 myRole
 * matrix of 006 («Участники» / «Редактировать чат» / «Удалить чат» vs
 * «Выйти из чата»). While the role is unknown (no live №28 and no
 * basis) the group header carries no gear — the menu matrix needs the
 * role. The prototype search button never joins the header (FR-016:
 * 013 is out of scope).
 *
 * 008a US4 (T057; FR-012, ui-behavior §4.1): bell-колокол звука —
 * `.ch-btn` SVG прототипа (#btnBell, chats.html:446) ПЕРЕД
 * шестернёнкой (место поиска 013 не отображается — колокол между ним
 * и шестернёнкой); живёт в ЛЮБОМ открытом чате (звук — персональная
 * настройка участия, роли/вида чата не требует). Состояние «выкл» —
 * только класс `.off` (opacity .45, chat-header.css — прототип :221),
 * aria-pressed — первое применение в проекте (вкл = pressed, умолчание
 * true — контракт №12/№13 «отсутствие поля клиент трактует как true»);
 * focus-visible — конвенция 008 FR-035 (.ch-btn:focus-visible). Сама
 * операция №42 (+ тосты, звук отклика, откат при ошибке) — у страницы
 * (onToggleSound), заголовок — проекция.
 *
 * 008a Phase 11 (T068; прототип #btnSearch chats.html:443): кнопка-
 * лупа псевдо-поиска — ПЕРВОЙ в .ch-btns, перед bell-колоколом. Клики
 * переключают режим поиска сайдбара: активный режим — класс `.on`
 * (золотая рамка прототипа :156) + aria-pressed; повторный клик
 * выключает и возвращает фокус композеру (прототип :1571 — фокус
 * принадлежит странице, onToggleSearch — провод). Активна только при
 * открытом чате — заголовок существует лишь у открытого окна.
 *
 * The avatar derives from the peer username (circle) or the group
 * title (octagon, FR-024) via ui/Avatar and recalculates on renames;
 * the title itself is resolved by the page — the live №28 title
 * while the view is open (the optimistic `group.updated` half), the
 * №12/№27 answer until then.
 *
 * Цепочка отображаемых имён (feature 008a, US1, T023; FR-003, ui-behavior
 * §1): заголовок ЛИЧНОГО чата и строки members-tip живут по
 * `alias → displayName → username` — direct берёт №11/№12/№13 peer
 * (`displayName`) + персональный `peerAlias` вызывающего (страница
 * сходится №12-рефетчем, ui-behavior §1.2), №28-строки подсказки —
 * `GroupMember.user` (`displayName`,`alias`). Инициалы — из цепочки
 * (`initials`-prop T020), цвет — от username (переименование не
 * перекрашивает, FR-004); усечение длинных имён — CSS с многоточием,
 * полный текст — в `title` (конвенция 008). Группа — title 006.
 */
import { useEffect, useLayoutEffect, useRef, useState, type RefObject } from 'react'
import { createPortal } from 'react-dom'
import type { GroupMember } from '../../api/groups'
import { Avatar } from '../../ui/Avatar'
import { initialsOf } from '../../ui/avatar'
import { resolveDisplayName } from '../../ui/names'
import { pluralRu, lastSeenFormat } from '../../ui/time'
import { usePresenceEntry } from '../../presence/usePresence'
import type { PresenceEntry } from '../../presence/presenceStore'
import { ChatGearMenu } from './ChatGearMenu'
import type { GearMenuChat } from './ChatGearMenu'
import './chat-header.css'

/** Header avatar size — design-tokens §4 (заголовок 46px). */
const HEADER_AVATAR_SIZE = 46

/** Members-tip avatar size — prototype `.mt-row .avatar` (24px). */
const TIP_AVATAR_SIZE = 24

/** Поле clamp-формул прототипа (showMembersTip: 8px). */
const TIP_VIEWPORT_MARGIN = 8

/** Отступ подсказки от низа якоря (прототип: r.bottom + 6). */
const TIP_ANCHOR_GAP = 6

/** The direct dialog of the header (the 004 pair dialog surface). */
export interface DirectChatHeaderData {
  /** The peer of the 1:1 dialog — the presence surface userId (007). */
  readonly peerId: string
  readonly username: string
  /** Профильное displayName peer №11/№12/№13 (008a T023; отсутствует → username). */
  readonly displayName?: string | null
  /** Персональный alias вызывающего к peer №11/№12/№13 (008a T023; верх цепочки). */
  readonly peerAlias?: string | null
  /** The blocker-side mark «заблокирован» (№12 projection, FR-020). */
  readonly blockedByMe: boolean
  /** №20 membership of the peer — the gear's «Добавить в контакты» visibility (T054). */
  readonly peerInContacts: boolean
}

/** The group window of the header (feature 006). */
export interface GroupChatHeaderData {
  /** Resolved by the page: the live №28 title or the №12/№27 answer. */
  readonly title: string
  /** Live №28 roster length or the №12/№13/№27 memberCount; null — unknown. */
  readonly memberCount: number | null
  /**
   * The live №28 roster — the members-tip source (US3 T043, FR-017);
   * null while №28 is not live (the №12 basis carries no names) —
   * the status row is no tip source until then.
   */
  readonly members: readonly GroupMember[] | null
  /**
   * The viewer's role — the live №28 myRole while the view is open,
   * the №12/№13/№27 basis until then (T054): the «владелец»/«админ»
   * marks of the tip rows (only rendered when №28 is live) AND the
   * gear menu matrix of 006 (ui-behavior §4); null — no basis at all,
   * the group header carries no gear.
   */
  readonly myRole: GroupMember['role'] | null
  /** The viewer's user id — filters the own №28 row out of the tip. */
  readonly meUserId: string | null
  /** The viewer's username — the «Вы» avatar derivation source. */
  readonly meUsername: string | null
}

/** One header, two variants — the page discriminates by `kind`. */
export type ChatHeaderChat =
  | ({ readonly kind: 'direct' } & DirectChatHeaderData)
  | ({ readonly kind: 'group' } & GroupChatHeaderData)

export interface ChatHeaderProps {
  /** The open dialog of either kind — drives avatar shape and status. */
  readonly chat: ChatHeaderChat
  /**
   * Персональный звук-переключатель открытого чата (008a US4, T057;
   * FR-012, ui-behavior §4.1): №12/№13 `soundEnabled` (или локальный
   * итог №42 у страницы); undefined/true — «вкл» (умолчание контракта:
   * отсутствие поля трактуется как true), false — «выкл» (.off).
   */
  readonly soundEnabled?: boolean
  /**
   * Клик bell-колокола (008a T057): страница владеет №42 — получает
   * ЦЕЛЕВОЕ значение (`!текущего`), по успеху сходится состояние, тост
   * и звук отклика (§4.2); ошибка — состояние не меняется.
   */
  readonly onToggleSound?: (next: boolean) => void
  /**
   * 008a Phase 11 (T068): режим псевдо-поиска открытого чата — кнопка-
   * лупа #btnSearch прототипа несёт класс `.on` пока режим активен;
   * клики ушли странице (включение/выключение + фокус композера).
   */
  readonly searchActive?: boolean
  /** Клик лупы (T068): страница переключает режим поиска сайдбара. */
  readonly onToggleSearch?: () => void
  /** direct: «Добавить в контакты» — №21 belongs to the page (T054). */
  readonly onAddContact?: () => void
  /** direct: «Заблокировать/Разблокировать контакт» — №23/№24 of the page. */
  readonly onToggleBlock?: () => void
  /** «Удалить чат» (подтверждение): direct №14 / group №30 — the page's. */
  readonly onDeleteChat?: () => void
  /** group: «Участники» → the members form of the shell (T055/T057). */
  readonly onOpenMembers?: () => void
  /** group owner/admin: «Редактировать чат» → group-edit of the shell (T056/T057). */
  readonly onOpenEdit?: () => void
  /** group member: «Выйти из чата» (danger, подтверждение) — №33 of the page. */
  readonly onLeaveChat?: () => void
}

/** «N участников» — the prototype membersCount (§7 chatView, ui/time pluralRu). */
function membersStatus(count: number): string {
  return `${count} ${pluralRu(count, 'участник', 'участника', 'участников')}`
}

/**
 * `.status-txt` личного чата (008a US3, T048; ui-behavior §3, FR-011):
 * online — «В сети» (007, без изменений); offline с раскрытым
 * `lastSeenAt` — «Был в сети — {время}» (`lastSeenFormat` T046 —
 * сегодня ЧЧ:ММ, иначе дата+время, год при отличии); offline без
 * поля — единый нейтральный фолбэк «Был в сети — давно» (нет данных /
 * инкогнито / блок-пара неотличимы для наблюдателя, SC-002).
 */
function directStatusText(entry: PresenceEntry): string {
  if (entry.status === 'online') {
    return 'В сети'
  }
  return `Был в сети — ${entry.lastSeenAt !== undefined ? lastSeenFormat(entry.lastSeenAt) : 'давно'}`
}

/** The tip rows (FR-017): the live №28 members minus me ([] until №28 is live). */
function tipMembersOf(chat: ChatHeaderChat): readonly GroupMember[] {
  if (chat.kind !== 'group' || chat.members === null) {
    return []
  }
  return chat.members.filter((member) => member.user.id !== chat.meUserId)
}

interface DirectStatusRowProps {
  /**
   * The merged 007 entry of the peer (008a T047/T048): status +
   * disclosed `lastSeenAt?`; undefined — no store entry yet («unknown»,
   * до первого №36/кадра №18).
   */
  readonly entry: PresenceEntry | undefined
  /** The blocker-side mark «заблокирован» (№12 projection, FR-020). */
  readonly blockedByMe: boolean
}

/**
 * The direct `.status-row` — the prototype presence lamp + label (US3
 * T043; 008a T048 — «Был в сети — …» при offline). Bug 12 (T090): the
 * neutral «unknown» is NOT displayed — no lamp, no `.status-txt` —
 * only the empty row stays (its min-height in chat-header.css holds
 * the header height) so the header does not jump when №36 converges;
 * the «заблокирован» mark (FR-020) is not a presence indicator and
 * stays.
 */
function DirectStatusRow({ entry, blockedByMe }: DirectStatusRowProps) {
  if (entry === undefined || entry.status === 'unknown') {
    return (
      <div className="status-row">
        {blockedByMe && <span className="chat-item-blocked">заблокирован</span>}
      </div>
    )
  }
  return (
    <div className="status-row">
      <span className={entry.status === 'offline' ? 'lamp off' : 'lamp'} aria-hidden="true" />
      <span className="status-txt">{directStatusText(entry)}</span>
      {blockedByMe && <span className="chat-item-blocked">заблокирован</span>}
    </div>
  )
}

interface GroupStatusRowProps {
  readonly chat: GroupChatHeaderData
  /** №28 live — the row is a focusable tip source; the №12 basis is inert. */
  readonly rosterLive: boolean
  readonly statusRowRef: RefObject<HTMLButtonElement | null>
  readonly openTip: () => void
  readonly closeTip: () => void
}

/**
 * The group `.status-row` — «N участников», the members-tip anchor
 * (US3 T043, FR-017): while №28 is live the row is a native `<button>`
 * — the focusable tip source (FR-035); until then a plain div — no
 * tip source, not focusable, hover a no-op. null — no memberCount yet.
 */
function GroupStatusRow({
  chat,
  rosterLive,
  statusRowRef,
  openTip,
  closeTip,
}: GroupStatusRowProps) {
  if (chat.memberCount === null) {
    return null
  }
  if (!rosterLive) {
    return (
      <div className="status-row">
        <span className="status-txt">{membersStatus(chat.memberCount)}</span>
      </div>
    )
  }
  return (
    <button
      type="button"
      className="status-row tip-source"
      ref={statusRowRef}
      tabIndex={0}
      onMouseEnter={openTip}
      onMouseLeave={closeTip}
      onFocus={openTip}
      onBlur={closeTip}
    >
      <span className="status-txt">{membersStatus(chat.memberCount)}</span>
    </button>
  )
}

interface MembersTipProps {
  /** The «Вы» avatar derivation source. */
  readonly meUsername: string | null
  /** The viewer's №28 role — the role mark of the «Вы» row (T096). */
  readonly myRole: GroupMember['role'] | null
  /** The live №28 members minus me (FR-017). */
  readonly members: readonly GroupMember[]
  readonly tipRef: RefObject<HTMLDivElement | null>
}

/**
 * The role mark text of a tip row (bug 18/T096): owner → «владелец»,
 * admin → «админ», member — no mark (null). The 006 role model is
 * finer than the prototype demo data — its single «администратор»
 * mark; the `.mt-me` lexica (chat-header.css/prototype §163) stays,
 * only the text splits.
 */
function roleMarkOf(role: GroupMember['role'] | null): string | null {
  if (role === 'owner') {
    return 'владелец'
  }
  if (role === 'admin') {
    return 'админ'
  }
  return null
}

/**
 * Цепочка имени участника №28 (008a T023; ui-behavior §1):
 * `GroupMember.user` несёт профильное `displayName` и персональный
 * `alias` вызывающего — единая точка `resolveDisplayName` (ui/names).
 */
function memberChainOf(member: GroupMember): string {
  return resolveDisplayName(member.user.alias, member.user.displayName, member.user.username)
}

/** The `.members-tip` roster body (FR-017): «Вы» first, then №28 minus me. */
function MembersTip({ meUsername, myRole, members, tipRef }: MembersTipProps) {
  const meMark = roleMarkOf(myRole)
  return (
    <div className="members-tip show" role="tooltip" ref={tipRef}>
      <div className="mt-title">Участники</div>
      <div className="mt-row">
        <Avatar source={meUsername ?? ''} size={TIP_AVATAR_SIZE} />
        <span className="mt-name">Вы</span>
        {meMark !== null && <span className="mt-me">{meMark}</span>}
      </div>
      {members.map((member) => {
        const mark = roleMarkOf(member.role)
        // 008a T023: цепочка имени строки, инициалы — из неё, цвет —
        // от username (FR-004); усечение — CSS, полный текст — в title.
        const displayName = memberChainOf(member)
        return (
          <div className="mt-row" key={member.user.id}>
            <Avatar
              source={member.user.username}
              initials={initialsOf(displayName)}
              size={TIP_AVATAR_SIZE}
            />
            <span className="mt-name" title={displayName}>
              {displayName}
            </span>
            {mark !== null && <span className="mt-me">{mark}</span>}
          </div>
        )
      })}
    </div>
  )
}

/**
 * The gear menu data of the header chat (T054): direct — the №20
 * membership of the peer + the block mark; group — the resolved role
 * (live №28, else the №12/№13/№27 basis); null — no basis yet, no
 * gear in the header.
 */
function gearChatOf(chat: ChatHeaderChat): GearMenuChat | null {
  if (chat.kind !== 'group') {
    return {
      kind: 'direct',
      peerInContacts: chat.peerInContacts,
      blockedByMe: chat.blockedByMe,
    }
  }
  return chat.myRole !== null ? { kind: 'group', myRole: chat.myRole } : null
}

export function ChatHeader({
  chat,
  soundEnabled,
  onToggleSound,
  searchActive = false,
  onToggleSearch,
  onAddContact,
  onToggleBlock,
  onDeleteChat,
  onOpenMembers,
  onOpenEdit,
  onLeaveChat,
}: ChatHeaderProps) {
  const isGroup = chat.kind === 'group'
  // 008a T023: direct — цепочка `peerAlias → displayName → username`
  // (ui-behavior §1), группа — title; инициалы — из имени, цвет — от
  // username/title (FR-004: переименование не перекрашивает аватар).
  const name = isGroup
    ? chat.title
    : resolveDisplayName(chat.peerAlias, chat.displayName, chat.username)
  const avatarSource = isGroup ? chat.title : chat.username
  const gearChat = gearChatOf(chat)

  // The 007 surface of the open 1:1 dialog (T040): the status comes
  // ONLY from the presenceStore — a group header registers nothing.
  // 008a US3 (T048): полная запись (status + lastSeenAt?) ведёт
  // «Был в сети — …» проекцию строки статуса.
  const presenceEntry = usePresenceEntry(!isGroup ? chat.peerId : null)

  // The members-tip anchor (the ContextMenu pattern): the status row
  // rect captured at open — null keeps the tip out of the DOM.
  const [tipAnchor, setTipAnchor] = useState<DOMRect | null>(null)
  const statusRowRef = useRef<HTMLButtonElement>(null)
  const tipRef = useRef<HTMLDivElement>(null)
  const closeTip = (): void => {
    setTipAnchor(null)
  }

  const rosterLive = isGroup && chat.members !== null
  const tipOpen = rosterLive && tipAnchor !== null

  const openTip = (): void => {
    if (!rosterLive) {
      return
    }
    const rect = statusRowRef.current?.getBoundingClientRect()
    if (rect !== undefined) {
      setTipAnchor(rect)
    }
  }

  // The tip rows (FR-017): «Вы» first, then the №28 members minus me.
  const tipMembers = tipMembersOf(chat)

  // Prototype clamp formulas (showMembersTip): left — no further than
  // the viewport edge less the measured width, top — under the anchor.
  // Re-clamped when the roster lands/grows under an open hover so the
  // growth never pushes the tip past the bottom edge.
  useLayoutEffect(() => {
    const tip = tipRef.current
    if (tipAnchor === null || tip === null) {
      return
    }
    const left = Math.max(
      TIP_VIEWPORT_MARGIN,
      Math.min(tipAnchor.left, window.innerWidth - tip.offsetWidth - TIP_VIEWPORT_MARGIN),
    )
    const top = Math.min(
      tipAnchor.bottom + TIP_ANCHOR_GAP,
      window.innerHeight - tip.offsetHeight - TIP_VIEWPORT_MARGIN,
    )
    tip.style.left = `${left}px`
    tip.style.top = `${top}px`
  }, [tipAnchor, tipMembers])

  // Esc closes the tip as the TOP layer (data-model 3.1: ctx-menu/
  // members-tip → modal → drawer) — capture + stopPropagation keep it
  // from falling through to a modal or the drawer below.
  useEffect(() => {
    if (!tipOpen) {
      return
    }
    const onKeyDown = (event: KeyboardEvent): void => {
      if (event.key === 'Escape') {
        event.stopPropagation()
        closeTip()
      }
    }
    document.addEventListener('keydown', onKeyDown, true)
    return () => {
      document.removeEventListener('keydown', onKeyDown, true)
    }
  }, [tipOpen])

  return (
    <header className="chat-head">
      <div className="head-av">
        <Avatar
          source={avatarSource}
          initials={initialsOf(name)}
          shape={isGroup ? 'octagon' : 'circle'}
          size={HEADER_AVATAR_SIZE}
        />
      </div>
      <div className="chat-title">
        <h2 className="chat-name dialog-title" title={name}>
          {name}
        </h2>
        {isGroup ? (
          <GroupStatusRow
            chat={chat}
            rosterLive={rosterLive}
            statusRowRef={statusRowRef}
            openTip={openTip}
            closeTip={closeTip}
          />
        ) : (
          <DirectStatusRow entry={presenceEntry} blockedByMe={chat.blockedByMe} />
        )}
      </div>
      {/* Правые кнопки: лупа псевдо-поиска (008a Phase 11 T068,
          прототип #btnSearch) — первой; bell-колокол звука (008a T057,
          ui-behavior §4.1) — всегда, персональная настройка участия;
          «шестерёнка» (FR-016, T054): direct — всегда, group — с
          известной ролью (живой №28 или базис №12/№13/№27) — без роли
          матрица пунктов 006 не определена. */}
      <div className="ch-btns">
        <button
          type="button"
          className={searchActive ? 'ch-btn on' : 'ch-btn'}
          title="Поиск"
          aria-pressed={searchActive}
          onClick={() => {
            onToggleSearch?.()
          }}
        >
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <circle cx="10.5" cy="10.5" r="6.5" />
            <path d="M15.5 15.5 21 21" />
          </svg>
        </button>
        <button
          type="button"
          className={soundEnabled === false ? 'ch-btn off' : 'ch-btn'}
          title="Звуковые оповещения"
          aria-pressed={soundEnabled !== false}
          onClick={() => {
            // ЦЕЛЕВОЕ значение: текущее «вкл» → выключить, «выкл» → включить.
            onToggleSound?.(soundEnabled === false)
          }}
        >
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="M18 16v-5a6 6 0 0 0-12 0v5l-1.5 2.5h15L18 16z" />
            <path d="M10 20a2 2 0 0 0 4 0" />
          </svg>
        </button>
        {gearChat !== null && (
          <ChatGearMenu
            chat={gearChat}
            onAddContact={onAddContact}
            onToggleBlock={onToggleBlock}
            onDeleteChat={onDeleteChat}
            onOpenMembers={onOpenMembers}
            onOpenEdit={onOpenEdit}
            onLeaveChat={onLeaveChat}
          />
        )}
      </div>
      {/* Подсказка состава (FR-017) — портал в body, как #membersTip
          прототипа: fixed-позиционирование относительно вьюпорта,
          поверх машины (z-56), pointer-events: none — чистый ховер. */}
      {tipOpen &&
        isGroup &&
        chat.members !== null &&
        createPortal(
          <MembersTip
            meUsername={chat.meUsername}
            myRole={chat.myRole}
            members={tipMembers}
            tipRef={tipRef}
          />,
          document.body,
        )}
    </header>
  )
}
