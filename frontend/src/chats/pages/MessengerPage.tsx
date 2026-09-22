/**
 * Messenger page (feature 004, T025): the application shell of the
 * messenger — the chats/contacts panel on the left and the open dialog
 * window on the right. Mounted on the protected route `/` (with the
 * `/chat` alias — the SSO landing path from feature 003).
 *
 * T025 scope is the layout only: the panel contents (ChatListPanel /
 * ContactList, T058/T059) and the dialog components (MessageList /
 * MessageInput / ErrorBanner, T026) mount into the slots below, the
 * page state (active chat, panel actions) is wired in T036/T060.
 */
export function MessengerPage() {
  return (
    <div className="messenger">
      <aside className="messenger-panel" aria-label="Чаты и контакты">
        <p className="messenger-empty">Разделы «Чаты» и «Контакты» появятся здесь</p>
      </aside>
      <section className="messenger-dialog" aria-label="Окно диалога">
        <p className="messenger-empty">Выберите чат, чтобы начать общение</p>
      </section>
    </div>
  )
}
