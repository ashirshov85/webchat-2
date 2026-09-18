import { useEffect, useState } from 'react'
import { linkSsoAuthorize, listIdentities, listSsoProviders, unlinkIdentity } from '../../api/sso'
import type { Identity, SsoProvider } from '../../api/sso'
import { problemMessage } from '../../auth/problem'

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
    <section>
      <h2>Account security</h2>
      {ssoErrorCode !== null && <p role="alert">{messageFor(ssoErrorCode)}</p>}
      {linkedProviderId !== null && (
        <p role="status">Provider {linkedProviderName()} was linked to your account.</p>
      )}
      {loadState === 'loading' && <output>Loading your sign-in methods…</output>}
      {loadState === 'error' && (
        <p role="alert">Could not load your sign-in methods. Please refresh the page.</p>
      )}
      {loadState === 'ready' && (
        <>
          <section aria-label="Linked providers">
            <h3>Linked providers</h3>
            {identities.length === 0 && <p>No providers are linked to your account yet.</p>}
            {identities.length > 0 && (
              <ul>
                {identities.map((identity) => (
                  <li key={identity.id}>
                    <span>{providerLabel(identity)}</span>
                    <span>{identity.email ?? '—'}</span>
                    <span>{formatLinkedAt(identity.linkedAt)}</span>
                    <button
                      type="button"
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
              <p role="alert">
                This is your last sign-in method, so it cannot be unlinked. Set a password first via
                the <a href="/forgot-password">password reset email</a>, then unlink the provider.
              </p>
            )}
            {unlinkError !== null && unlinkError.kind === 'message' && (
              <p role="alert">{unlinkError.text}</p>
            )}
          </section>
          {linkableProviders.length > 0 && (
            <section aria-label="Link a provider">
              <h3>Link a provider</h3>
              {linkError !== null && <p role="alert">{linkError}</p>}
              {linkableProviders.map((provider) => (
                <button
                  key={provider.id}
                  type="button"
                  disabled={linkingId !== null}
                  onClick={() => void linkProvider(provider)}
                >
                  {linkingId === provider.id ? 'Redirecting…' : `Link ${provider.displayName}`}
                </button>
              ))}
            </section>
          )}
        </>
      )}
    </section>
  )
}
