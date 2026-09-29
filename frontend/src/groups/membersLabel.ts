/**
 * Russian pluralization of the group member counter (feature 006):
 * №12 `memberCount` of a chat row (T028) and the roster header of the
 * group info card (T046) render the same «N участник/участника/
 * участников» label — one source for both projections of the roster
 * size (1–200, the contract limit).
 */

/** 1/21 участник, 3 участника, 5/11 участников. */
export function membersLabel(count: number): string {
  const mod10 = count % 10
  const mod100 = count % 100
  if (mod10 === 1 && mod100 !== 11) {
    return 'участник'
  }
  if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) {
    return 'участника'
  }
  return 'участников'
}
