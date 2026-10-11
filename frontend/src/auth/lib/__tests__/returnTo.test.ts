import { describe, expect, it } from 'vitest'
import { isSafeReturnPath, loginRedirectTarget } from '../returnTo'

const CHAT_PATH = '/chat'

describe('isSafeReturnPath', () => {
  it.each([
    ['/chat'],
    ['/settings/security'],
    ['/chats/42/thread'],
    ['/chat?thread=42'],
    ['/chat#unread'],
    ['/settings/security?tab=sessions#sessions'],
    ['/file..name.txt'],
  ])('accepts an in-app path (%s)', (value) => {
    expect(isSafeReturnPath(value)).toBe(true)
  })

  it.each([
    ['protocol-relative url', '//evil.example'],
    ['absolute url', 'https://evil.example'],
    ['empty string', ''],
    ['null', null],
    ['backslash after slash (browser-normalized to //)', '/\\evil.com'],
    ['backslash inside path', '/foo\\bar'],
    ['traversal segment in the middle', '/a/../b'],
    ['traversal segment at the end', '/a/b/..'],
    ['lone traversal segment', '/..'],
    ['traversal segment before a query', '/a/../b?x=1'],
    ['newline control character', '/foo\nbar'],
    ['tab control character', '/foo\tbar'],
    ['carriage return control character', '/chat\r'],
    ['auth route /login', '/login'],
    ['auth route /login with query', '/login?x=1'],
    ['auth route /register', '/register'],
    ['auth route /confirm-registration', '/confirm-registration'],
    ['auth route /set-password', '/set-password'],
    ['auth route /forgot-password', '/forgot-password'],
    ['auth route /reset-password', '/reset-password'],
    ['auth route /sso/callback', '/sso/callback'],
    ['auth route /sso/callback with safe-looking query', '/sso/callback?next=/chat'],
  ])('rejects %s (%s)', (_reason, value) => {
    expect(isSafeReturnPath(value as unknown as string)).toBe(false)
  })

  it('checks the raw string without percent-decoding (browser keeps pct-encoded form on assign)', () => {
    expect(isSafeReturnPath('/foo%2e%2e/bar')).toBe(true)
    expect(isSafeReturnPath('/foo%5cbar')).toBe(true)
  })
})

describe('loginRedirectTarget', () => {
  it('returns the safe path itself', () => {
    expect(loginRedirectTarget('/settings/security')).toBe('/settings/security')
  })

  it('returns a safe path with query and fragment intact', () => {
    expect(loginRedirectTarget('/chat?thread=42#unread')).toBe('/chat?thread=42#unread')
  })

  it.each([
    ['missing returnTo', null],
    ['empty returnTo', ''],
    ['protocol-relative url', '//evil.example'],
    ['absolute url', 'https://evil.example'],
    ['backslash traversal', '/\\evil.com'],
    ['dot traversal', '/a/../b'],
    ['auth route', '/login'],
    ['auth route with query', '/login?x=1'],
  ])('falls back to /chat for %s', (_reason, value) => {
    expect(loginRedirectTarget(value)).toBe(CHAT_PATH)
  })
})
