/**
 * Prototype harness (feature 008, T016): `prototypeTest` boots the normative
 * design prototype `specs/008-chat-window-styling/design/chats.html` over
 * `file://` inside the shared visual-suite projects (1440×900 / 900×700 /
 * 480×800), so its states can be captured as the committed SC-001 baselines.
 *
 * Font determinism: the prototype <link>s its three families from Google
 * Fonts, so a baseline would depend on CDN reachability and could drift from
 * the implementation, which self-hosts the very same faces via @fontsource.
 * The harness intercepts `fonts.googleapis.com` and serves a stylesheet
 * assembled from the @fontsource packages installed in `node_modules` —
 * every woff2 inlined as a data URI (fontsource ships Google Fonts builds,
 * so glyphs render byte-identically to the app's fonts). A request that
 * ever reaches `fonts.gstatic.com` fails loudly with a 404: it would mean
 * the interception is incomplete.
 */
import { expect, test as base } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const fixturesDir = dirname(fileURLToPath(import.meta.url))
const frontendRoot = resolve(fixturesDir, '../../..')

/** Normative visual reference of the feature (SC-001). */
export const PROTOTYPE_PATH = resolve(
  frontendRoot,
  '../specs/008-chat-window-styling/design/chats.html',
)

/** Size of the prototype demo catalogue — the harness settle marker. */
export const PROTOTYPE_CHAT_COUNT = 8

/** The three prototype families at the exact weights/styles its header requests. */
const FONT_SHEETS: ReadonlyArray<{ pkg: string; sheets: ReadonlyArray<string> }> = [
  { pkg: '@fontsource/old-standard-tt', sheets: ['400.css', '700.css', '400-italic.css'] },
  { pkg: '@fontsource/cormorant-sc', sheets: ['400.css', '600.css', '700.css'] },
  { pkg: '@fontsource/anonymous-pro', sheets: ['400.css', '700.css', '400-italic.css'] },
]

let fontCssCache: string | undefined

/** Google-Fonts-replacement CSS: @fontsource faces with inlined woff2 sources. */
function localFontCss(): string {
  fontCssCache ??= FONT_SHEETS.flatMap(({ pkg, sheets }) => {
    const pkgDir = join(frontendRoot, 'node_modules', pkg)
    return sheets.map((sheet) =>
      readFileSync(join(pkgDir, sheet), 'utf8').replace(
        /url\(\.\/files\/([^)]+\.woff2)\) format\('woff2'\),\s*url\(\.\/files\/([^)]+\.woff)\) format\('woff'\)/g,
        (_match: string, woff2: string): string => {
          const data = readFileSync(join(pkgDir, 'files', woff2)).toString('base64')
          return `url(data:font/woff2;base64,${data}) format('woff2')`
        },
      ),
    )
  }).join('\n')
  return fontCssCache
}

export interface PrototypeHarness {
  readonly page: Page
  /** Rows of the demo catalogue in the prototype №12 order. */
  readonly chatRows: Locator
  /** Opens a demo chat by its prototype id — `alex`, `maria`, `phoenix`, … */
  openChat(id: string): Promise<void>
  /** Opens the «three stripes» main menu next to the search field. */
  openMainMenu(): Promise<void>
  /** Picks an item of the currently open main menu by label substring. */
  chooseMainMenu(label: string): Promise<void>
}

export const prototypeTest = base.extend<{ prototype: PrototypeHarness }>({
  prototype: async ({ page }, run) => {
    await page.route('https://fonts.googleapis.com/**', (route) =>
      route.fulfill({ contentType: 'text/css; charset=utf-8', body: localFontCss() }),
    )
    await page.route('https://fonts.gstatic.com/**', (route) => route.fulfill({ status: 404 }))
    await page.goto(`file://${PROTOTYPE_PATH}`)
    // data-URI faces resolve immediately; await the swap so no shot shows fallback glyphs
    await page.evaluate(async () => {
      await document.fonts.ready
    })
    const chatRows = page.locator('#chatList .contact')
    await expect(chatRows).toHaveCount(PROTOTYPE_CHAT_COUNT)
    await run({
      page,
      chatRows,
      openChat: async (id) => {
        await page.locator(`#chatList .contact[data-id="${id}"]`).click()
        await expect(page.locator(`#chatList .contact[data-id="${id}"]`)).toHaveClass(
          /(^|\s)active/,
        )
      },
      openMainMenu: async () => {
        await page.locator('#menuBtn').click()
        await expect(page.locator('#mainMenu.show')).toBeVisible()
      },
      chooseMainMenu: async (label) => {
        await page.locator('#mainMenu .ctx-item', { hasText: label }).first().click()
      },
    })
  },
})
