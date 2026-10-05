import { useEffect, useState } from 'react'
import { linkSsoAuthorize, listIdentities, listSsoProviders, unlinkIdentity } from '../../api/sso'
import type { Identity, SsoProvider } from '../../api/sso'
import { problemMessage } from '../../auth/problem'
import './settings.css'

type LoadState = 'loading' | 'ready' | 'error'

type UnlinkError = { kind: 'lastLoginMethod' } | { kind: 'message'; text: string }

const SSO_ERROR_MESSAGES: Record<string, string> = {
  invalid_state: 'The linking session is invalid or has expired. Please start again.',
  provider_disabled: 'This provider is currently disabled.',
  provider_error: 'The provider failed. Please try again later.',
  email_not_verified: 'The provider did not confirm your email address.',
  identity_taken: 'This identity is already linked to another account.',
  rejected: 'Linking was rejected.',
}

function messageFor(code: string): string {
  return SSO_ERROR_MESSAGES[code] ?? 'Linking failed. Please try again.'
}

function queryParam(name: string): string | null {
  const value = new URLSearchParams(window.location.search).get(name)
  return value !== null && value !== '' ? value : null
}

function providerLabel(identity: Identity): string {
  return identity.providerDisplayName ?? identity.providerId
}

function formatLinkedAt(iso: string): string {
  return new Date(iso).toLocaleDateString()
}

function isLastLoginMethodProblem(error: unknown): boolean {
  if (typeof error !== 'object' || error === null) {
    return false
  }
  const { status, errors } = error as { status?: unknown; errors?: unknown }
  if (status !== 409 || typeof errors !== 'object' || errors === null) {
    return false
  }
  const identity = (errors as Record<string, unknown>).identity
  return Array.isArray(identity) && identity.includes('last_login_method')
}

export function SecurityPage() {
  /**
   * T058 (FR-031): restyled into the «Aethergram» design system — the
   * page is a .panel of the machine (settings.css): banners/statuses in
   * the state-strip lexicon of status-banners.css (danger wash /
   * patient brass / toast-green), identity rows as .modal-ro-style
   * fields, buttons as the .m-btn family (primary «Link», danger
   * «Unlink»). Routes, semantics and texts are unchanged (FR-034).
   */
  const [linkedProviderId] = useState(() => queryParam('linked'))
  const [ssoErrorCode] = useState(() => queryParam('sso_error'))
  const [loadState, setLoadState] = useState<LoadState>('loading')
  const [identities, setIdentities] = useState<Identity[]>([])
  const [providers, setProviders] = useState<SsoProvider[]>([])
  const [linkingId, setLinkingId] = useState<string | null>(null)
  const [linkError, setLinkError] = useState<string | null>(null)
  const [unlinkingId, setUnlinkingId] = useState<string | null>(null)
  const [unlinkError, setUnlinkError] = useState<UnlinkError | null>(null)

  useEffect(() => {
    let cancelled = false
    Promise.all([listIdentities(), listSsoProviders()])
      .then(([identitiesResponse, providersResponse]) => {
        if (cancelled) {
          return
        }
        setIdentities(identitiesResponse.identities)
        setProviders(providersResponse.providers)
        setLoadState('ready')
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

  function linkedProviderName(): string {
    const provider = providers.find((candidate) => candidate.id === linkedProviderId)
    return provider?.displayName ?? linkedProviderId ?? ''
  }

  async function refreshIdentities(): Promise<void> {
    const response = await listIdentities()
    setIdentities(response.identities)
  }

  async function linkProvider(provider: SsoProvider): Promise<void> {
    setLinkError(null)
    setLinkingId(provider.id)
    try {
      const { authorizationUrl } = await linkSsoAuthorize({ providerId: provider.id })
      window.location.assign(authorizationUrl)
    } catch (err) {
      setLinkError(problemMessage(err))
      setLinkingId(null)
    }
  }

  async function unlink(identity: Identity): Promise<void> {
    setUnlinkError(null)
    setUnlinkingId(identity.id)
    try {
      await unlinkIdentity(identity.id)
      await refreshIdentities()
    } catch (err) {
      setUnlinkError(
        isLastLoginMethodProblem(err)
          ? { kind: 'lastLoginMethod' }
          : { kind: 'message', text: problemMessage(err) },
      )
    } finally {
      setUnlinkingId(null)
    }
  }

  const linkableProviders = providers.filter(
    (provider) => !identities.some((identity) => identity.providerId === provider.id),
  )

  return (
    <section className="panel settings-page">
      <h2 className="settings-title">Account security</h2>
      {ssoErrorCode !== null && (
        <p className="settings-error" role="alert">
          {messageFor(ssoErrorCode)}
        </p>
      )}
      {linkedProviderId !== null && (
        <output className="settings-ok">
          Provider {linkedProviderName()} was linked to your account.
        </output>
      )}
      {loadState === 'loading' && (
        <output className="settings-status">Loading your sign-in methods…</output>
      )}
      {loadState === 'error' && (
        <p className="settings-error" role="alert">
          Could not load your sign-in methods. Please refresh the page.
        </p>
      )}
      {loadState === 'ready' && (
        <>
          <section aria-label="Linked providers" className="settings-block">
            <h3 className="settings-subtitle">Linked providers</h3>
            {identities.length === 0 && (
              <p className="settings-empty">No providers are linked to your account yet.</p>
            )}
            {identities.length > 0 && (
              <ul className="settings-identity-list">
                {identities.map((identity) => (
                  <li key={identity.id} className="settings-identity">
                    <span className="s-id-main">
                      <span className="s-id-name">{providerLabel(identity)}</span>
                      <span className="s-id-email">{identity.email ?? '—'}</span>
                    </span>
                    <span className="s-id-date">{formatLinkedAt(identity.linkedAt)}</span>
                    <button
                      type="button"
                      className="settings-btn danger"
                      disabled={unlinkingId !== null}
                      onClick={() => void unlink(identity)}
                    >
                      {unlinkingId === identity.id ? 'Unlinking…' : 'Unlink'}
                    </button>
                  </li>
                ))}
              </ul>
            )}
            {unlinkError !== null && unlinkError.kind === 'lastLoginMethod' && (
              <p className="settings-error" role="alert">
                This is your last sign-in method, so it cannot be unlinked. Set a password first via
                the <a href="/forgot-password">password reset email</a>, then unlink the provider.
              </p>
            )}
            {unlinkError !== null && unlinkError.kind === 'message' && (
              <p className="settings-error" role="alert">
                {unlinkError.text}
              </p>
            )}
          </section>
          {linkableProviders.length > 0 && (
            <section aria-label="Link a provider" className="settings-block">
              <h3 className="settings-subtitle">Link a provider</h3>
              {linkError !== null && (
                <p className="settings-error" role="alert">
                  {linkError}
                </p>
              )}
              <div className="settings-provider-actions">
                {linkableProviders.map((provider) => (
                  <button
                    key={provider.id}
                    type="button"
                    className="settings-btn primary"
                    disabled={linkingId !== null}
                    onClick={() => void linkProvider(provider)}
                  >
                    {linkingId === provider.id ? 'Redirecting…' : `Link ${provider.displayName}`}
                  </button>
                ))}
              </div>
            </section>
          )}
        </>
      )}
    </section>
  )
}
