import { useEffect, useState } from 'react'
import { problemMessage } from '../../auth/problem'
import { fetchPresenceSettings, updatePresenceSettings } from '../../presence/presenceApi'

type LoadState = 'loading' | 'ready' | 'error'

/**
 * №38 «incognito» profile toggle (feature 007, T034; quickstart QS-4):
 * the mode is per-user and persisted server-side (users.presence_hidden,
 * PG V15), so it survives relogin — GET on mount reflects the saved
 * value, PUT flips it. While incognito the user appears offline to
 * everyone (indistinguishable from a real offline, FR-007) and still
 * sees everyone else's statuses.
 */
export function PresenceSettingsPage() {
  const [loadState, setLoadState] = useState<LoadState>('loading')
  const [incognito, setIncognito] = useState(false)
  const [saving, setSaving] = useState(false)
  const [saveError, setSaveError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    fetchPresenceSettings()
      .then((settings) => {
        if (!cancelled) {
          setIncognito(settings.incognito)
          setLoadState('ready')
        }
      })
      .catch(() => {
        if (!cancelled) {
          setLoadState('error')
        }
      })
    return () => {
      cancelled = true
    }
  }, [])

  async function toggle(next: boolean): Promise<void> {
    setSaveError(null)
    setSaving(true)
    try {
      const settings = await updatePresenceSettings(next)
      setIncognito(settings.incognito)
    } catch (err) {
      setIncognito(!next)
      setSaveError(problemMessage(err))
    } finally {
      setSaving(false)
    }
  }

  return (
    <section>
      <h2>Presence visibility</h2>
      {loadState === 'loading' && <output>Loading your presence settings…</output>}
      {loadState === 'error' && (
        <p role="alert">Could not load your presence settings. Please refresh the page.</p>
      )}
      {loadState === 'ready' && (
        <section aria-label="Incognito mode">
          <h3>Incognito mode</h3>
          <p>
            While incognito you appear offline to everyone — indistinguishable from being really
            offline — and you keep seeing everyone else&apos;s statuses. The mode applies to your
            whole account, not a single device, and stays after you sign in again.
          </p>
          <label>
            <input
              type="checkbox"
              checked={incognito}
              disabled={saving}
              onChange={(event) => void toggle(event.target.checked)}
            />{' '}
            Incognito mode
          </label>
          {saveError !== null && <p role="alert">{saveError}</p>}
        </section>
      )}
    </section>
  )
}
