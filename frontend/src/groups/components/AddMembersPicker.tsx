/**
 * «Добавить из контактов» multiselect (feature 006, T026; FR-002):
 * a purely presentational roster of the ADDER's №20 contacts as
 * checkboxes — the picker owns no data and no network, so the same
 * component serves the №27 initial roster inside CreateGroupDialog
 * and the №31 batch inside GroupInfoPanel (T046).
 *
 * Selection state stays with the parent: a Set preserving the pick
 * order becomes `memberUserIds` (№27) / `userIds` (№31) verbatim.
 * `excludeUserIds` hides entries that must not be offered again —
 * e.g. users already active in the group for №31 (FR-002
 * idempotency itself is server-owned); the creator cannot meet
 * themselves in their own contacts (004 №21 `self_forbidden`), so
 * the №27 usage passes no exclusions.
 */
import type { ContactView } from '../../api/chats'

export interface AddMembersPickerProps {
  /** The adder's №20 contacts, in the server-owned alphabetical order. */
  readonly contacts: readonly ContactView[]
  /** Ids currently picked (insertion order = batch order). */
  readonly selectedIds: ReadonlySet<string>
  /** Toggles one contact's membership in the picked batch. */
  readonly onToggle: (userId: string) => void
  /** Entries to hide from the offer (already active members, №31). */
  readonly excludeUserIds?: ReadonlySet<string>
  /** Hint rendered when there is nobody left to offer. */
  readonly emptyHint?: string
}

export function AddMembersPicker({
  contacts,
  selectedIds,
  onToggle,
  excludeUserIds,
  emptyHint,
}: AddMembersPickerProps) {
  const offered =
    excludeUserIds === undefined
      ? contacts
      : contacts.filter((contact) => !excludeUserIds.has(contact.user.id))

  if (offered.length === 0) {
    return (
      <p className="messenger-empty">
        {emptyHint ?? 'Нет контактов, которых можно добавить в группу'}
      </p>
    )
  }

  return (
    <fieldset className="member-picker">
      <legend className="member-picker-legend">Участники из контактов</legend>
      <ul className="member-picker-items" aria-label="Контакты для добавления">
        {offered.map((contact) => (
          <li key={contact.user.id} className="member-picker-row">
            <label className="member-picker-row-main">
              <input
                type="checkbox"
                aria-label={`Выбрать ${contact.user.username}`}
                checked={selectedIds.has(contact.user.id)}
                onChange={() => {
                  onToggle(contact.user.id)
                }}
              />
              <span className="chat-item-title">{contact.user.username}</span>
              <span className="contact-row-email">{contact.user.email}</span>
            </label>
          </li>
        ))}
      </ul>
      <p className="member-picker-count">Выбрано: {selectedIds.size}</p>
    </fieldset>
  )
}
