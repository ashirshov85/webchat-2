/**
 * T025 (US1-AS5, FR-004): proves the `prefers-reduced-motion: reduce`
 * kill-switch of theme/machine.css actually reaches the US1 surfaces of
 * the running app — the prototype's global pattern (design-tokens §8)
 * collapses every animation (flick/pop/tickStamp/puff) and transition to
 * a ~0.01s single iteration, while the readability end-states (bubbles,
 * ticks, presence dots) and the static machine patterns (grain, vignette,
 * golden frame) stay untouched.
 *
 * No pixel baseline is needed: motion is verified through computed
 * styles on the real stylesheet in a real Chromium, so the spec is cheap
 * enough to run in every config project (the motion kill-switch is
 * viewport-independent; mobile-only states are US5).
 *
 * Note on `10ms`: the CSS minifier rewrites the authored `0.01s` of
 * machine.css to `10ms` in the production bundle — the spec normalizes
 * durations to seconds before comparing.
 */
import { expect } from '@playwright/test'
import { visualTest } from './fixtures/app'
import { AETHERGRAM_CHAT_COUNT } from './fixtures/aethergram'

/** Boots the messenger, then switches reduce on and reloads, so the app
 *  runs under the system setting from its first paint (routes and the
 *  init script persist across the reload). */
const reducedMotionTest = visualTest.extend({
  messenger: async ({ messenger }, run) => {
    await messenger.page.emulateMedia({ reducedMotion: 'reduce' })
    await messenger.page.reload()
    await expect(messenger.page.locator('.chat-item')).toHaveCount(AETHERGRAM_CHAT_COUNT)
    await run(messenger)
  },
})

/** Window channel of the in-page puff probe (see the tickStamp test). */
interface PuffProbeWindow {
  __puffSnapshots?: string[]
}

/** Authored duration strings of the production bundle (`0.01s` source may
 *  ship as `10ms`) normalized to seconds. */
function toSeconds(value: string): number {
  if (value.endsWith('ms')) {
    return Number.parseFloat(value) / 1000
  }
  return Number.parseFloat(value)
}

/** Forces the element's CSS animations to their end state
 *  (`Animation.finish()`), so end-state reads (fill `both`/`forwards`)
 *  are deterministic even on an idle headless page where no animation
 *  frames are produced and the 10ms flight would never tick. */
async function finishAnimations(locator: import('@playwright/test').Locator) {
  await locator.first().evaluate((element) => {
    for (const animation of element.getAnimations({ subtree: true })) {
      animation.finish()
    }
  })
}

reducedMotionTest.describe('prefers-reduced-motion on US1 surfaces (T025)', () => {
  reducedMotionTest(
    'machine.css ships the prototype global kill-switch rule',
    async ({ messenger }) => {
      const rule = await messenger.page.evaluate(() => {
        for (const sheet of Array.from(document.styleSheets)) {
          let rules: CSSRuleList
          try {
            rules = sheet.cssRules
          } catch {
            continue
          }
          for (const candidate of Array.from(rules)) {
            if (
              candidate instanceof CSSMediaRule &&
              candidate.conditionText.includes('prefers-reduced-motion')
            ) {
              for (const inner of Array.from(candidate.cssRules)) {
                if (inner instanceof CSSStyleRule && inner.selectorText.split(',').includes('*')) {
                  return {
                    animationDuration: inner.style.getPropertyValue('animation-duration'),
                    animationDurationImportant:
                      inner.style.getPropertyPriority('animation-duration') === 'important',
                    iterationCount: inner.style.getPropertyValue('animation-iteration-count'),
                    iterationCountImportant:
                      inner.style.getPropertyPriority('animation-iteration-count') === 'important',
                    transitionDuration: inner.style.getPropertyValue('transition-duration'),
                    transitionDurationImportant:
                      inner.style.getPropertyPriority('transition-duration') === 'important',
                  }
                }
              }
            }
          }
        }
        return null
      })
      // The universal selector is what covers flick/tickStamp/puff on the
      // surfaces where they mount later (US3 ticks/dots) — design-tokens §8.
      expect(rule).not.toBeNull()
      expect(toSeconds(rule?.animationDuration ?? '')).toBeCloseTo(0.01)
      expect(rule?.animationDurationImportant).toBe(true)
      expect(rule?.iterationCount).toBe('1')
      expect(rule?.iterationCountImportant).toBe(true)
      expect(toSeconds(rule?.transitionDuration ?? '')).toBeCloseTo(0.01)
      expect(rule?.transitionDurationImportant).toBe(true)
    },
  )

  reducedMotionTest(
    'pop entrance collapses instantly and stays readable',
    async ({ messenger }) => {
      await messenger.openChat('night to remember')
      // The feed auto-scrolls to the newest row; on small viewports the
      // older rows sit unpainted under content-visibility, so style reads
      // target the newest row and settle for attached (paint is the T026
      // screenshot suite's business).
      const row = messenger.page.locator('.msg').last()
      await expect(row).toBeAttached()
      await finishAnimations(row)
      const styles = await row.evaluate((element) => {
        const computed = getComputedStyle(element)
        return {
          name: computed.animationName,
          duration: computed.animationDuration,
          iterations: computed.animationIterationCount,
          opacity: computed.opacity,
          transform: computed.transform,
        }
      })
      expect(styles.name).toBe('pop')
      expect(toSeconds(styles.duration)).toBeCloseTo(0.01)
      expect(styles.iterations).toBe('1')
      // Fill mode `both` lands on the readable end-state at once (Chromium
      // serializes the `transform: none` keyframe as the identity matrix).
      expect(styles.opacity).toBe('1')
      expect(['none', 'matrix(1, 0, 0, 1, 0, 0)']).toContain(styles.transform)
      await expect(messenger.page.getByText('Will do. See you at the tower!')).toBeAttached()
    },
  )

  reducedMotionTest('flick presence dot is static and fully lit', async ({ messenger }) => {
    // US1 surfaces render feed avatars without dots yet (US3/T042), so
    // the flick rule is probed by mounting a real .av-dot into an
    // existing avatar and reading the live stylesheet's computed result.
    await messenger.openChat('night to remember')
    await expect(messenger.page.locator('.message-list .msg .avatar').last()).toBeAttached()
    const dot = await messenger.page.evaluate(() => {
      const host = document.querySelector('.message-list .msg .avatar')
      if (host === null) {
        return null
      }
      const element = document.createElement('i')
      element.className = 'av-dot'
      host.appendChild(element)
      const computed = getComputedStyle(element)
      const snapshot = {
        name: computed.animationName,
        duration: computed.animationDuration,
        iterations: computed.animationIterationCount,
        opacity: computed.opacity,
      }
      element.remove()
      return snapshot
    })
    expect(dot).not.toBeNull()
    expect(dot?.name).toBe('flick')
    expect(toSeconds(dot?.duration ?? '')).toBeCloseTo(0.01)
    expect(dot?.iterations).toBe('1')
    // One 10ms iteration ends on the 100% keyframe — opacity 1 — so the
    // online lamp reads as a steady green dot (readability).
    expect(dot?.opacity).toBe('1')
  })

  reducedMotionTest(
    'US1 control transitions collapse to an instant state change',
    async ({ messenger }) => {
      await messenger.openChat('night to remember')
      for (const selector of ['.chat-panel-search', '.send-btn']) {
        const duration = await messenger.page
          .locator(selector)
          .first()
          .evaluate((element) => getComputedStyle(element).transitionDuration)
        expect(toSeconds(duration.split(',')[0] ?? duration), selector).toBeCloseTo(0.01)
      }
    },
  )

  reducedMotionTest(
    'tickStamp ack and composer steam are killed, sending still works',
    async ({ messenger }) => {
      await messenger.openChat('night to remember')
      await messenger.page.locator('#message-composer').fill('Puff under reduced motion')

      // The three steam puffs live only 1.4s in the DOM, so their
      // end-state is snapshotted INSIDE the page at spawn time: a
      // MutationObserver on the .steam container force-finishes every
      // spawned animation (`finish()` needs no animation frames — an
      // idle headless page may never produce any) and records the
      // computed opacity. Under reduce the 10ms `forwards` flight means
      // every puff must read as fully transparent — no flying steam.
      await messenger.page.evaluate(() => {
        const steam = document.querySelector('.steam')
        if (steam === null) {
          return
        }
        const record = () => {
          const existing = (window as PuffProbeWindow).__puffSnapshots ?? []
          for (const puff of Array.from(steam.querySelectorAll('.puff'))) {
            for (const animation of puff.getAnimations()) {
              animation.finish()
            }
            existing.push(getComputedStyle(puff).opacity)
          }
          ;(window as PuffProbeWindow).__puffSnapshots = existing
        }
        new MutationObserver(record).observe(steam, { childList: true })
      })
      await messenger.page.getByRole('button', { name: 'ОТПРАВИТЬ' }).click()
      const puffOpacities = await messenger.page.evaluate(
        () => (window as PuffProbeWindow).__puffSnapshots ?? [],
      )
      expect(puffOpacities).toEqual(['0', '0', '0'])

      // The ack replaces the optimistic row and its fresh tick mounts with
      // .anim (T023) — tickStamp must finish at once: single fast
      // iteration settled on the readable engraved state.
      const tick = messenger.page.locator('.tick.anim').first()
      await expect(tick).toBeAttached()
      await finishAnimations(tick)
      const tickStyles = await tick.locator('svg').evaluate((element) => {
        const computed = getComputedStyle(element)
        return {
          name: computed.animationName,
          duration: computed.animationDuration,
          iterations: computed.animationIterationCount,
          opacity: computed.opacity,
          transform: computed.transform,
        }
      })
      expect(tickStyles.name).toBe('tickStamp')
      expect(toSeconds(tickStyles.duration)).toBeCloseTo(0.01)
      expect(tickStyles.iterations).toBe('1')
      // No fill mode on tickStamp — once finished, the engraved tick is
      // its plain static self (`none` may read as the identity matrix).
      expect(tickStyles.opacity).toBe('1')
      expect(['none', 'matrix(1, 0, 0, 1, 0, 0)']).toContain(tickStyles.transform)

      // The composer keeps working under reduce: the sent text renders.
      await expect(messenger.page.getByText('Puff under reduced motion')).toBeAttached()
    },
  )

  reducedMotionTest(
    'static machine patterns survive the motion kill-switch',
    async ({ messenger }) => {
      const patterns = await messenger.page.evaluate(() => {
        const grain = getComputedStyle(document.body, '::before')
        const vignette = getComputedStyle(document.body, '::after')
        const machine = document.querySelector('.machine')
        const frame = machine === null ? null : getComputedStyle(machine)
        return {
          grainOpacity: grain.opacity,
          grainImage: grain.backgroundImage,
          vignetteImage: vignette.backgroundImage,
          machineVisible:
            frame !== null &&
            frame.borderTopWidth !== '' &&
            frame.borderTopColor !== 'rgba(0, 0, 0, 0)' &&
            frame.boxShadow !== 'none',
        }
      })
      expect(patterns.grainOpacity).toBe('0.55')
      expect(patterns.grainImage).toContain('data:image/svg+xml')
      expect(patterns.vignetteImage).toContain('radial-gradient')
      expect(patterns.machineVisible).toBe(true)
    },
  )
})
