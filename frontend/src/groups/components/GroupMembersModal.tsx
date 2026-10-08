/**
 * Модальная форма «Участники» группового чата (feature 008, US4, T055 →
 * T095/Bug 17; FR-023, ui-behavior §3, research §D): житель ЕДИНОЙ
 * оболочки ModalShell (MessengerPage, formId 'group-members'),
 * проекция #membersForm нормативного прототипа
 * specs/008-chat-window-styling/design/chats.html (renderMembersList):
 * ростер №28 минус собственная строка — «Вы» живёт только в members-tip
 * заголовка, старая пометка «{username} (вы)» ушла вместе с own-строкой
 * (контракт T049).
 *
 * Метки ролей 006 («Владелец»/«Админ»/«Участник») — дословно из
 * MemberList (миграция T049, FR-034/SC-002), строка — визуальный
 * паритет «Контактов» (.pick-row лексика ctc-row: аватар,
 * .c-main/.c-top/.c-name, метка роли в .c-prev).
 *
 * T095 (Bug 17): ростер-действия №32/№34/№35 и «Добавить в контакты»
 * живут в ContextMenu кнопки-кебаба «⋯» строки (title «Действия с
 * участником», паттерн .c-menu ContactsModal) — строго по иерархии
 * myRole (owner — всем кроме себя; admin — kick только member-строк,
 * №34/№35 owner-only; member — кебаба нет вовсе), пункты несут
 * aria-label'ы «Исключить/Назначить админом/Снять админа/Передать
 * владение {username}» (SC-002), «Исключить» — danger-пункт. В
 * прототипе «⋯»-меню участников нет (renderMembersList) — производная
 * лексика ContextMenu + ctc-row, baseline T060 по реализованному
 * снимку (класс исключения T060).
 *
 * `pendingUserId` — отображение мьютекса useGroupMembers (кебаб строки
 * в полёте disabled), `rosterError` — последняя проблема ростер-действия
 * (ErrorBanner). «Добавить в контакты» — пункт вне-книжных строк (№20):
 * модаль сообщает userId наверх (onAddContact), №21 и его тост
 * принадлежат странице (T045).
 *
 * T098 (Bug 20): строка участника — НАВИГАЦИЯ (требование владельца;
 * паттерн ctc-строки «Контактов»): клик открывает переписку с
 * участником — direct-чат есть в №12 (проп `chats`) → `onOpenChat`
 * со связанным ChatView БЕЗ похода на сервер (№12-базис — тот же,
 * из которого строка «Чатов» открывает direct-окно: активному окну
 * нужны только chatId/peer/blockedByMe, водяные знаки лента живёт
 * своим №13/№15-конвейером); чата нет → №11 `ensureChat({peerUserId})`
 * → `onOpenChat` (создание и открытие, как «Создать чат» из «⋯»
 * «Контактов»); сбой №11 — инлайн-ошибка `.modal-err` при живом
 * списке (паттерн модалей). Закрытие оболочки — у владельца
 * (`onOpenChat` = штатный проп handleOpenChatFromModal, как у
 * «Контактов»). Навигация доступна ВСЕМ ролям — членство в группе
 * даёт право писать (006), myRole на неё не влияет; прототип
 * membersList строк-кликов не несёт (renderMembersList) — отступление
 * от демо-прототипа по требованию владельца, класс исключения T060
 * (задокументировано здесь и в CSS).
 */
import { useMemo, useState, type MouseEvent as ReactMouseEvent } from 'react'
import { ensureChat, type ChatListItem, type ChatView } from '../../api/chats'
import type { GroupMember } from '../../api/groups'
import { problemMessage } from '../../auth/problem'
import { ErrorBanner } from '../../chats/components/ErrorBanner'
import { Avatar } from '../../ui/Avatar'
import { ContextMenu, type MenuItem } from '../../ui/ContextMenu'
import './group-members-modal.css'

export interface GroupMembersModalProps {
  /** №28 active roster (server-owned order, ≤200 — FR-013). */
  readonly members: readonly GroupMember[]
  /** The viewer's role in the group — gates the row actions (FR-004). */
  readonly myRole: GroupMember['role']
  /** The viewer's user id — the own row is NOT rendered (ui-behavior §3). */
  readonly currentUserId: string
  /** №12-строки (useChatList MessengerPage): базис direct-чата строки (T098). */
  readonly chats: readonly ChatListItem[]
  /** Открывает переписку (№11/№12 ChatView): клик по строке участника (T098). */
  readonly onOpenChat: (chat: ChatView) => void
  /** №32 kick (owner: admins+members; admin: members only). */
  readonly onKick: (userId: string) => void
  /** №34 grant/revoke admin (owner-only). */
  readonly onSetRole: (userId: string, role: 'admin' | 'member') => void
  /** №35 transfer ownership (owner-only, never to self). */
  readonly onTransferOwnership: (userId: string) => void
  /** The member with an in-flight roster action (useGroupMembers mutex view). */
  readonly pendingUserId?: string | null
  /** The last roster-action problem (useGroupMembers `error`). */
  readonly rosterError?: unknown
  /** User ids already in the adder's address book (№20) — hides the offer. */
  readonly contactUserIds?: ReadonlySet<string>
  /** «Добавить в контакты» — the parent owns №21 and its toast (T045/T057). */
  readonly onAddContact?: (userId: string) => void
}

const ROLE_LABELS: Readonly<Record<GroupMember['role'], string>> = {
  owner: 'Владелец',
  admin: 'Админ',
  member: 'Участник',
}

/** FR-004 row-action set: what the viewer may do to THIS member. */
interface RowActions {
  readonly kick: boolean
  readonly grantAdmin: boolean
  readonly revokeAdmin: boolean
  readonly transferOwnership: boolean
}

const NO_ACTIONS: RowActions = {
  kick: false,
  grantAdmin: false,
  revokeAdmin: false,
  transferOwnership: false,
}

function actionsFor(myRole: GroupMember['role'], target: GroupMember): RowActions {
  if (target.role === 'owner') {
    // The single owner row is untouchable by any viewer (the owner
    // himself does not see it — the own row is filtered out above;
    // №32/№34/№35 on the owner is `role_hierarchy_violation`).
    return NO_ACTIONS
  }
  if (myRole === 'owner') {
    return {
      kick: true,
      grantAdmin: target.role === 'member',
      revokeAdmin: target.role === 'admin',
      transferOwnership: true,
    }
  }
  if (myRole === 'admin') {
    // FR-004: an admin excludes only plain members; №34/№35 are
    // owner-only (`not_group_owner`).
    return target.role === 'member' ? { ...NO_ACTIONS, kick: true } : NO_ACTIONS
  }
  return NO_ACTIONS
}

/** Есть ли у строки пункты ростер-действий (для разделителя «Добавить в контакты»). */
function hasRosterActions(actions: RowActions): boolean {
  return actions.kick || actions.grantAdmin || actions.revokeAdmin || actions.transferOwnership
}

/** Direct-чат с участником в №12 (type у direct-элементов опционален). */
function directChatOf(chats: readonly ChatListItem[], userId: string): ChatListItem | null {
  for (const item of chats) {
    if ((item.type ?? 'direct') === 'direct' && item.peer?.id === userId) {
      return item
    }
  }
  return null
}

export function GroupMembersModal({
  members,
  myRole,
  currentUserId,
  chats,
  onOpenChat,
  onKick,
  onSetRole,
  onTransferOwnership,
  pendingUserId = null,
  rosterError = null,
  contactUserIds,
  onAddContact,
}: GroupMembersModalProps) {
  // renderMembersList прототипа: собственная строка не выводится вовсе.
  const rows = members.filter((member) => member.user.id !== currentUserId)

  const [menuMember, setMenuMember] = useState<GroupMember | null>(null)
  const [menuAnchor, setMenuAnchor] = useState<DOMRect | null>(null)
  /** Сбой №11-навигации строки — инлайн-ошибка при живом списке (T098). */
  const [navError, setNavError] = useState<string | null>(null)

  /**
   * T098: клик по строке — открыть переписку с участником. Чат есть в
   * №12 → onOpenChat со связанным ChatView сразу (№12-базис — тот же,
   * каким строка «Чатов» открывает direct-окно; водяные знаки ленты
   * живут своим №13/№15-конвейером); чата нет → №11 создаёт пару и
   * открывает (как «Создать чат» из «⋯» «Контактов»), сбой — инлайн-
   * ошибка, список остаётся живым (паттерн модалей).
   */
  const handleRowActivate = (member: GroupMember) => {
    const bound = directChatOf(chats, member.user.id)
    if (bound !== null && bound.peer !== null) {
      setNavError(null)
      onOpenChat({
        type: 'direct',
        chatId: bound.chatId,
        peer: bound.peer,
        blockedByMe: bound.blockedByMe ?? false,
        peerReadUpToSeq: null,
        myReadUpToSeq: 0,
      })
      return
    }
    setNavError(null)
    void Promise.resolve(ensureChat({ peerUserId: member.user.id }))
      .then((view) => {
        onOpenChat(view)
      })
      .catch((error: unknown) => {
        setNavError(problemMessage(error))
      })
  }

  /** Кебаб «⋯»: якорь — rect кнопки (паттерн ContactsModal); клик не
   * проваливается в строку (menuBtn-early-return прототипа). */
  const handleMenuButtonClick = (
    event: ReactMouseEvent<HTMLButtonElement>,
    member: GroupMember,
  ) => {
    event.stopPropagation()
    setMenuAnchor(event.currentTarget.getBoundingClientRect())
    setMenuMember(member)
  }

  /** Пункты «⋯»-меню строки (T049/T055 дословно): порядок матрицы
   * myRole + «Добавить в контакты» вне-книжных строк (после
   * разделителя — ростер-действия и адресная книга — разные группы
   * действий, паттерн «Удалить контакт» ContactsModal). */
  const menuItems = useMemo<readonly MenuItem[]>(() => {
    if (menuMember === null) {
      return []
    }
    const username = menuMember.user.username
    const actions = actionsFor(myRole, menuMember)
    const offerContact =
      contactUserIds !== undefined && onAddContact !== undefined
        ? !contactUserIds.has(menuMember.user.id)
        : false
    const items: readonly (MenuItem | null)[] = [
      actions.kick
        ? {
            label: 'Исключить',
            ariaLabel: `Исключить ${username}`,
            danger: true,
            onSelect: () => {
              onKick(menuMember.user.id)
            },
          }
        : null,
      actions.grantAdmin
        ? {
            label: 'Назначить админом',
            ariaLabel: `Назначить админом ${username}`,
            onSelect: () => {
              onSetRole(menuMember.user.id, 'admin')
            },
          }
        : null,
      actions.revokeAdmin
        ? {
            label: 'Снять админа',
            ariaLabel: `Снять админа ${username}`,
            onSelect: () => {
              onSetRole(menuMember.user.id, 'member')
            },
          }
        : null,
      actions.transferOwnership
        ? {
            label: 'Передать владение',
            ariaLabel: `Передать владение ${username}`,
            onSelect: () => {
              onTransferOwnership(menuMember.user.id)
            },
          }
        : null,
      offerContact
        ? {
            label: 'Добавить в контакты',
            sepBefore: hasRosterActions(actions),
            onSelect: () => {
              onAddContact?.(menuMember.user.id)
            },
          }
        : null,
    ]
    return items.filter((item): item is MenuItem => item !== null)
  }, [menuMember, myRole, contactUserIds, onKick, onSetRole, onTransferOwnership, onAddContact])

  return (
    <div className="members-form">
      {rosterError !== null && <ErrorBanner error={rosterError} />}
      <div className="pick-list members-list">
        {rows.length === 0 ? (
          <div className="pick-empty">Нет участников</div>
        ) : (
          rows.map((member) => {
            const actions = actionsFor(myRole, member)
            const pending = pendingUserId === member.user.id
            const username = member.user.username
            // Кебаб несёт меню — рендерим только когда пункты есть:
            // ростер-действия по матрице myRole ИЛИ «Добавить в контакты»
            // (member-зритель кебаба не видит вовсе — T095).
            // №20: предложение — только строкам вне адресной книги
            // вызывающего (собственная строка уже отфильтрована).
            const offerContact =
              contactUserIds !== undefined && onAddContact !== undefined
                ? !contactUserIds.has(member.user.id)
                : false
            const showMenu = hasRosterActions(actions) || offerContact
            return (
              // T098: строка — навигация (паттерн ctc-строки «Контактов»):
              // button лексики pick-row/ctc-row, доступное имя «Участник
              // {username}», title «Открыть чат»; кебаб-клик гасится
              // stopPropagation'ом обработчика кебаба (как .c-menu
              // ContactsModal). Доступна всем ролям (006).
              <button
                type="button"
                key={member.user.id}
                className="pick-row ctc-row member-row"
                aria-label={`Участник ${username}`}
                title="Открыть чат"
                onClick={() => {
                  handleRowActivate(member)
                }}
              >
                <Avatar source={username} size={32} />
                <div className="c-main">
                  <div className="c-top">
                    <span className="c-name">{username}</span>
                  </div>
                  <div className="c-prev">{ROLE_LABELS[member.role]}</div>
                </div>
                {showMenu && (
                  <button
                    type="button"
                    className="c-menu"
                    title="Действия с участником"
                    aria-haspopup="menu"
                    aria-expanded={menuMember?.user.id === member.user.id}
                    disabled={pending}
                    onClick={(event) => {
                      handleMenuButtonClick(event, member)
                    }}
                  >
                    <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
                      <circle cx="12" cy="5" r="1.8" />
                      <circle cx="12" cy="12" r="1.8" />
                      <circle cx="12" cy="19" r="1.8" />
                    </svg>
                  </button>
                )}
              </button>
            )
          })
        )}
      </div>
      {/* T098: сбой №11-навигации строки — инлайн-ошибка при живом
          списке (паттерн модалей, лексика .modal-err ContactsModal). */}
      {navError !== null && (
        <div className="modal-err" role="alert">
          {navError}
        </div>
      )}
      <ContextMenu
        open={menuMember !== null}
        anchor={menuMember !== null ? menuAnchor : null}
        items={menuItems}
        onClose={() => {
          setMenuMember(null)
        }}
      />
    </div>
  )
}
