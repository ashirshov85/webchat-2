import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { MockInstance } from 'vitest'
import { App } from './App'
import { stubLocationAssign } from './test/location'

const REFRESH_TOKEN_STORAGE_KEY = 'webchat.auth.refreshToken'

let assignMock: MockInstance

function navigate(pathname: string): void {
  window.history.pushState({}, '', pathname)
}

function authenticate(): void {
  window.localStorage.setItem(REFRESH_TOKEN_STORAGE_KEY, 'refresh-token-1')
}

beforeEach(() => {
  assignMock = stubLocationAssign()
})

afterEach(() => {
  cleanup()
  window.localStorage.clear()
  assignMock.mockRestore()
  navigate('/')
  vi.clearAllMocks()
})

describe('App', () => {
  it('does not render the application heading on any route', () => {
    navigate('/forgot-password')
    const publicPage = render(<App />)

    expect(screen.queryByRole('heading', { level: 1, name: 'WebChat' })).not.toBeInTheDocument()

    publicPage.unmount()
    navigate('/set-password')
    render(<App />)

    expect(screen.queryByRole('heading', { level: 1 })).not.toBeInTheDocument()
    cleanup()

    authenticate()
    navigate('/')
    render(<App />)

    expect(screen.getByRole('complementary', { name: 'Чаты и контакты' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { level: 1 })).not.toBeInTheDocument()
  })
})

describe('App protected messenger route', () => {
  it('redirects an unauthenticated visitor of / to the login page with returnTo', () => {
    navigate('/')
    render(<App />)

    expect(assignMock).toHaveBeenCalledWith('/login?returnTo=%2F')
    expect(screen.queryByRole('complementary', { name: 'Чаты и контакты' })).not.toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Окно диалога' })).not.toBeInTheDocument()
  })

  it('redirects an unauthenticated visitor of /chat back to the login page', () => {
    navigate('/chat')
    render(<App />)

    expect(assignMock).toHaveBeenCalledWith('/login?returnTo=%2Fchat')
  })

  it('renders the messenger layout for an authenticated user on /', () => {
    authenticate()
    navigate('/')
    render(<App />)

    expect(screen.getByRole('complementary', { name: 'Чаты и контакты' })).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Окно диалога' })).toBeInTheDocument()
    expect(assignMock).not.toHaveBeenCalled()
  })

  it('renders the messenger layout for an authenticated user on /chat', () => {
    authenticate()
    navigate('/chat')
    render(<App />)

    expect(screen.getByRole('region', { name: 'Окно диалога' })).toBeInTheDocument()
    expect(assignMock).not.toHaveBeenCalled()
  })
})
