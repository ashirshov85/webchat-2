/**
 * T047 — US3 visual regression (SC-001 in the US3 scope): the US3
 * details of the running app — presence-decorated avatars (T042), the
 * ЧЧ:ММ feed footers with the date divider (T044) and the group
 * members tip (T043) — are compared against the committed T016(б)
 * baselines, the very PNGs `prototype-baselines.spec.ts` keeps
 * capturing from the normative prototype `design/chats.html` on its
 * demo dataset (research §B).
 *
 * Coverage audit (the T046 pattern — close the gap, keep the suite
 * tight): the first two surfaces are already pinned by the shots the
 * earlier stories own and stay green through the T042/T044 runs —
 * the catalogue avatars with their №36 dots ride the T026 region
 * (`us1-chat-list.png`) and the T039 fullscreens (`us2-sidebar.png`,
 * `us2-contacts-modal.png`), while the timed feed with its
 * «19 сентября» divider rides the T026 feed region
 * (`us1-direct-chat-feed.png` — the T044 implementation was pulled
 * forward, so the baseline carries the details). The one T016(б)
 * baseline no spec consumed yet is `us3-members-tip.png` — captured
 * from the prototype explicitly for T047 (see
 * prototype-baselines.spec.ts). Its fullscreen state — «Project
 * Phoenix» open under the hovered status row — inherently carries
 * all three US3 surfaces at once: the sidebar avatars with the №36
 * presence dots, the group feed with its ЧЧ:ММ footers and the top
 * date divider of the exhausted history, and the `.members-tip.show`
 * roster over the window.
 *
 * State parity with the T016(б) capture: the app reaches the state
 * through the very interaction path the prototype harness used — the
 * phoenix row click, then the hover of the status row (mouseenter on
 * #statusRow ≙ `.status-row`), so the active row highlight and the
 * hover residue match the baseline too.
 *
 * Before the pixels, the US3 details are pinned as DOM preconditions
 * (the T039 state-assert pattern): the dot count/classes of the
 * catalogue (the №36 convergence of research §B — 3 online + 2 off),
 * the divider label and the three ЧЧ:ММ footers of the phoenix feed,
 * and the tip roster («Вы» + «администратор» first, then the №28
 * members in the prototype order). The pixel budget is the SC-001
 * `maxDiffPixelRatio ≈ 0.02` of the config; the derived avatar
 * colours (FR-024) and the prototype-only own-row dot of the tip
 * stay within it by design (research §E).
 */
import { expect } from '@playwright/test'
import { visualTest } from './fixtures/app'

const DESKTOP = 'chromium-1440x900'

/** Restricts the shot to the viewport project it is normative for. */
function onlyProject(project: string): void {
  visualTest.skip(visualTest.info().project.name !== project, `${project} viewport only`)
}

/** Shared capture options — the T016 recipe (see the file header). */
const SHOT = { animations: 'disabled', caret: 'hide' } as const

visualTest.describe('T047 — US3 details against the T016(б) baselines', () => {
  visualTest('members tip over the presence-dotted machine', async ({ messenger }) => {
    onlyProject(DESKTOP)
    const { page } = messenger

    // «Project Phoenix» — the group dialog of the us3-members-tip
    // baseline; its row keeps the active highlight the capture depicts.
    await messenger.openChat('Project Phoenix')
    await expect(page.locator('.chat-name')).toHaveText('Project Phoenix')

    // US3 avatars with presence (T042): every direct row of the
    // catalogue carries its №36 dot — alex, maria and eleanor online,
    // james and thomas off; the three group rows carry none.
    await expect(page.locator('.chat-item .av-dot')).toHaveCount(5)
    await expect(page.locator('.chat-item .av-dot.off')).toHaveCount(2)

    // US3 feed details (T044) of the open group window: the top date
    // divider of the exhausted history and a ЧЧ:ММ footer per row.
    await expect(page.locator('.message-list .date-divider')).toHaveText('19 сентября')
    await expect(page.locator('.message-list .b-time')).toHaveCount(3)

    // №28 must be live before the hover: only then the status row is
    // a tip source and the feed rows carry their №28 sender names.
    await expect(page.locator('.chat-head .status-row.tip-source')).toBeVisible()

    // The members tip (T043) — the prototype harness path: hover the
    // group status row, the portalled .members-tip.show opens.
    await page.locator('.chat-head .status-row').hover()
    const tip = page.locator('.members-tip.show')
    await expect(tip).toBeVisible()
    await expect(tip.locator('.mt-title')).toHaveText('Участники')
    // «Вы» first with the «администратор» mark (myRole owner), then
    // the №28 members in the prototype order.
    await expect(tip.locator('.mt-row')).toHaveCount(4)
    expect(await tip.locator('.mt-name').allTextContents()).toEqual([
      'Вы',
      'Maria Lopez',
      'James Whitmore',
      'Alex Carter',
    ])
    await expect(tip.locator('.mt-row').first().locator('.mt-me')).toHaveText('администратор')

    await expect(page).toHaveScreenshot('us3-members-tip.png', SHOT)
  })
})
