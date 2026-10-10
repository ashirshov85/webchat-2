/**
 * Left panel of the messenger (feature 004, T058; FR-013/014): the
 * «Чаты» list itself. The data comes from useChatList (T057) — the
 * server-owned №12 ordering (last visible message first, messageless
 * chats below) plus the live event updates; the panel is render-only
 * and adds no requests.
 *
 * Unified list + search (feature 006, T028; FR-014): direct dialogs and
 * groups render in ONE «Чаты» list — a group row shows its `title`, a
 * direct row the peer login (ChatListItem discriminates by `type`). The
 * search field filters the RENDERED rows live, case-insensitively, by
 * the group title or the peer login: the server keeps owning the
 * ordering, the panel only filters what it already renders (no extra
 * requests); clearing the query restores the full list.
 *
 * «Чаты» states: loading, error with a retry (the list refetches via
 * `onReload` — the same path SSE reconnects use), empty (a first
 * incoming from a stranger lands here automatically, FR-019), the
 * list itself and the «Ничего не найдено» hint of an over-narrow
 * query. Rows (ChatListItem) render the preview, the unread badge
 * («99+», exact internal count — the optimistic cache of the
 * server-authoritative counter that useChatList converges over №12,
 * №26 sync deltas and realtime frames, feature 005 T035) and the
 * «заблокирован» mark (FR-014/020); clicking a row opens the pair
 * dialog via `onSelectChat`, the open one is highlighted by
 * `activeChatId`.
 *
 * «Aethergram» reskin (feature 008, US1, T019; FR-001): the search
 * field rides the PROTOTYPE search row — `.search-row >
 * .search-wrap` with the engraved magnifier glyph and the golden
 * frame of `#search` (design/chats.html §5, chat-list-panel.css) —
 * while the 004 test hook `chat-panel-search` stays as the input's
 * class (research §C, FR-034).
 *
 * Feature 008 (US2, T029; FR-006, research §D, ui-behavior §2): the
 * «Чаты»/«Контакты» mode tabs are GONE — the chat list renders right
 * away and the search filters it by the chat NAME (FR-010: group
 * title / peer login — never by message content, that is feature
 * 013). Contacts are no longer a sidebar section: they live in the
 * ContactsModal (T031/T032) opened through the MainMenuButton of the
 * search row (T030), so the panel carries no contacts content prop
 * anymore. The 004 reachability of every contact operation is
 * preserved — only the entry point moves (FR-034, SC-004).
 *
 * 008a Phase 11 (T068; прототип renderSearchResults chats.html:858):
 * режим ПСЕВДО-ПОИСКА по сообщениям открытого чата — кнопка-лупа
 * заголовка (#btnSearch) переключает список «Чаты» ↔ «Поиск по
 * чату»: поле несёт placeholder/aria-label «Поиск по чату…» и
 * управляется СТРАНИЦЕЙ (запрос — её состояние, поиск живой по
 * вводу), список рендерит шапку .sr-head «Поиск по чату — {имя}»,
 * подсказки .sr-hint пустого запроса/отсутствия совпадений и
 * строки-результаты .sr-row.contact (аватар отправителя, имя —
 * свои «Вы», время, сниппет ~96 символов вокруг вхождения); клик по
 * строке уходит странице (переход к сообщению ленты). Результаты
 * строит страница по загруженному окну ленты — панель только
 * проекция, без собственных запросов. Включение режима фокусирует
 * поле (прототип :1572 $('#search').focus()).
 */
import { useEffect, useRef, useState } from 'react'
import type { ChatListItem as ChatListItemData } from '../../api/chats'
import type { ChatListStatus } from '../hooks/useChatList'
import { Avatar } from '../../ui/Avatar'
import { ErrorBanner } from './ErrorBanner'
import { ChatListItem } from './ChatListItem'
import { MainMenuButton } from './MainMenuButton'
import './chat-list-panel.css'
import './states.css'

/**
 * FR-014 row matching: a group answers by its `title`, a direct dialog
 * by the peer login — both case-insensitively. A group whose №12
 * aggregate has not landed yet (a bare realtime row) matches nothing:
 * the aggregate converges on the next №12 refetch.
 */
function chatMatchesQuery(item: ChatListItemData, normalizedQuery: string): boolean {
  if (normalizedQuery === '') {
    return true
  }
  const label = item.type === 'group' ? item.title : item.peer?.username
  return label !== undefined && label.toLowerCase().includes(normalizedQuery)
}

/**
 * Строка-результат псевдо-поиска (T068): проекция сообщения открытого
 * чата, собранная страницей (имя отправителя по цепочке US1 — свои
 * «Вы», группа — участник №28, direct — peer; аватар: инициалы из
 * имени, цвет — от username, FR-004; сниппет — ui/search). message не
 * проходит сюда типом — паналь остаётся свободной от API-типов ленты.
 */
export interface ChatSearchResultRow {
  /** id сообщения ленты — якорь перехода (jumpToMessage). */
  readonly messageId: string
  /** Отображаемое имя отправителя («Вы» — свои). */
  readonly senderName: string
  /** Источник цвета аватара (username отправителя). */
  readonly avatarSource: string
  /** Инициалы — из отображаемого имени отправителя. */
  readonly avatarInitials: string
  /** Время сообщения (formatTime). */
  readonly time: string
  /** Сниппет ~96 символов вокруг вхождения (ui/search). */
  readonly snippet: string
}

export interface ChatListPanelProps {
  /** №12 aggregate rows as maintained by useChatList (already ordered). */
  readonly chats: readonly ChatListItemData[]
  /** List fetch state from useChatList; defaults to `ready`. */
  readonly status?: ChatListStatus
  /** The error behind the `error` status (problemMessage renders it). */
  readonly error?: unknown
  /** Refetches the list (error retry; the hook's `reload`). */
  readonly onReload?: () => void
  /** The open dialog: its row renders highlighted. */
  readonly activeChatId?: string | null
  /** Opens the pair dialog when a row is clicked. */
  readonly onSelectChat?: (chatId: string) => void
  /** Current user id: passed through to rows for the «Вы:» preview prefix. */
  readonly currentUserId?: string | null
  /**
   * US2 (T030/T034): the MainMenuButton of the search row opens the
   * single ModalShell forms hosted by MessengerPage — «Мой профиль»
   * (profile), «Контакты» (contacts), «Создать групповой чат»
   * (create-group). The panel is only the entry point (passed through
   * to MainMenuButton).
   */
  readonly onOpenProfile?: () => void
  readonly onOpenContacts?: () => void
  readonly onCreateGroup?: () => void
  /**
   * 008a Phase 11 (T068): режим псевдо-поиска — сайдбар рендерит
   * результаты вместо чатов (запрос и фильтрация — у страницы).
   */
  readonly searchMode?: boolean
  /** Текущий запрос режима поиска (управляемое поле). */
  readonly searchQuery?: string
  /** Имя открытого чата для шапки (цепочка US1); null — окно не открыто. */
  readonly searchChatName?: string | null
  /** Строки-результаты по текущему запросу (собраны страницей). */
  readonly searchResults?: readonly ChatSearchResultRow[]
  /** Ввод запроса (живой поиск). */
  readonly onSearchQueryChange?: (query: string) => void
  /** Клик по строке-результату — переход к сообщению ленты. */
  readonly onJumpToMessage?: (messageId: string) => void
}

export function ChatListPanel({
  chats,
  status = 'ready',
  error = null,
  onReload,
  activeChatId = null,
  onSelectChat,
  currentUserId = null,
  onOpenProfile,
  onOpenContacts,
  onCreateGroup,
  searchMode = false,
  searchQuery = '',
  searchChatName = null,
  searchResults = [],
  onSearchQueryChange,
  onJumpToMessage,
}: ChatListPanelProps) {
  const [query, setQuery] = useState('')
  const normalizedQuery = query.trim().toLowerCase()
  const visibleChats =
    normalizedQuery === '' ? chats : chats.filter((item) => chatMatchesQuery(item, normalizedQuery))
  const searchFieldRef = useRef<HTMLInputElement>(null)
  const trimmedSearchQuery = searchQuery.trim()

  // Включение режима фокусирует поле и чистит локальный запрос обычного
  // режима (прототип :1571 — value='' + focus; выход тоже чистит).
  useEffect(() => {
    if (searchMode) {
      setQuery('')
      searchFieldRef.current?.focus()
    }
  }, [searchMode])

  return (
    <div className="chat-panel">
      <div className="search-row">
        <MainMenuButton
          onOpenProfile={onOpenProfile}
          onOpenContacts={onOpenContacts}
          onCreateGroup={onCreateGroup}
        />
        <div className="search-wrap">
          <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
            <circle cx="10.5" cy="10.5" r="6.5" />
            <path d="M15.5 15.5 21 21" />
          </svg>
          <input
            type="search"
            className="chat-panel-search"
            aria-label={searchMode ? 'Поиск по чату…' : 'Поиск чатов'}
            placeholder={searchMode ? 'Поиск по чату…' : 'Поиск…'}
            autoComplete="off"
            ref={searchFieldRef}
            value={searchMode ? searchQuery : query}
            onChange={(event) => {
              const next = event.target.value
              if (searchMode) {
                onSearchQueryChange?.(next)
                return
              }
              setQuery(next)
            }}
          />
        </div>
      </div>
      {searchMode ? (
        <ul className="chat-list" aria-label="Результаты поиска">
          <li className="sr-head">
            {searchChatName !== null ? `Поиск по чату — ${searchChatName}` : 'Нет открытого чата'}
          </li>
          {trimmedSearchQuery === '' && (
            <li className="sr-hint">Введите запрос — результаты появятся здесь</li>
          )}
          {trimmedSearchQuery !== '' &&
            searchResults.map((row) => (
              <li key={row.messageId}>
                <button
                  type="button"
                  className="contact sr-row"
                  title="Перейти к сообщению"
                  onClick={() => {
                    onJumpToMessage?.(row.messageId)
                  }}
                >
                  <Avatar source={row.avatarSource} initials={row.avatarInitials} />
                  <span className="c-main">
                    <span className="c-top">
                      <span className="c-name" title={row.senderName}>
                        {row.senderName}
                      </span>
                      <span className="c-time">{row.time}</span>
                    </span>
                    <span className="c-prev" title={row.snippet}>
                      {row.snippet}
                    </span>
                  </span>
                </button>
              </li>
            ))}
          {trimmedSearchQuery !== '' && searchResults.length === 0 && (
            <li className="sr-hint">Ничего не найдено</li>
          )}
        </ul>
      ) : (
        <>
          {status === 'loading' && <p className="messenger-empty">Загрузка чатов…</p>}
          {status === 'error' && (
            <div className="chat-panel-error">
              <ErrorBanner error={error} />
              {onReload !== undefined && (
                <button
                  type="button"
                  className="chat-panel-retry"
                  onClick={() => {
                    onReload()
                  }}
                >
                  Повторить
                </button>
              )}
            </div>
          )}
          {status === 'ready' && chats.length === 0 && (
            <p className="messenger-empty">Диалогов пока нет</p>
          )}
          {status === 'ready' && chats.length > 0 && visibleChats.length === 0 && (
            <p className="messenger-empty">Ничего не найдено</p>
          )}
          {visibleChats.length > 0 && (
            <ul className="chat-list" aria-label="Список чатов">
              {visibleChats.map((item) => (
                <ChatListItem
                  key={item.chatId}
                  item={item}
                  currentUserId={currentUserId}
                  active={item.chatId === activeChatId}
                  onSelect={onSelectChat}
                />
              ))}
            </ul>
          )}
        </>
      )}
    </div>
  )
}
