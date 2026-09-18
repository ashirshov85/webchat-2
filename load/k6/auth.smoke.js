import http from 'k6/http'
import { check, fail, sleep } from 'k6'

const BASE_URL = __ENV.K6_BASE_URL || 'http://localhost:8080'
const MAILPIT_URL = __ENV.K6_MAILPIT_URL || 'http://localhost:8025'

const API_BASE = `${BASE_URL}/api/v1`
const LOGIN_ENDPOINT = `${API_BASE}/auth/login`
const REGISTER_ENDPOINT = `${API_BASE}/auth/register`
const CONFIRM_ENDPOINT = `${API_BASE}/auth/register/confirm`
const SET_PASSWORD_ENDPOINT = `${API_BASE}/auth/register/password`
const SSO_PROVIDERS_ENDPOINT = `${API_BASE}/auth/sso/providers`
const SSO_AUTHORIZE_ENDPOINT = `${API_BASE}/auth/sso/authorize`
const SSO_TOKEN_ENDPOINT = `${API_BASE}/auth/sso/token`
const HEALTH_ENDPOINT = `${BASE_URL}/actuator/health`

const LOGIN_BURST_RPS = 280
const LOGIN_BURST_DURATION = '45s'
const REGISTER_BURST_RPS = 50
const REGISTER_BURST_DURATION = '30s'
const VALID_LOGINS_PER_MINUTE = 8
const HEALTH_PROBES_PER_MINUTE = 6
const SMOKE_DURATION = '3m'

// 003-sso T050 (research §12): SSO smoke slice. The probe rate stays well
// below the per-IP route limits (providers 30/1m, authorize 10/1m,
// token 30/1m — research §9), so the scenario asserts the happy statuses,
// not the 429 path already exercised by the bursts above.
const SSO_PROBES_PER_MINUTE = 3
const SSO_SOURCE_IP = '198.18.0.2'
// Id-shaped but never configured: authorize answers the uniform 404 for
// unknown and disabled providers alike (contracts/sso-api.md §2).
const SSO_UNKNOWN_PROVIDER_ID = 'k6-absent-provider'
// Syntactically valid (^[A-Za-z0-9_-]{43}$) but never issued: handshake codes
// are single-use server-side secrets, so any constant is an unknown code and
// POST /auth/sso/token must answer the uniform 400 invalid_code (§4).
const SSO_INVALID_HANDSHAKE_CODE = 'k6-smoke-invalid-handshake-code'.padEnd(43, 'x')

const SEED_USER_COUNT = 6
const VALID_SOURCE_IP = '198.18.0.1'
const VERIFICATION_LINK_PATTERN = /confirm-registration\?token=([A-Za-z0-9_-]{43})/
const MAILPIT_WAIT_MS = 90000

// k6 re-evaluates module scope per VU: Date.now()-derived values differ between
// setup() and scenario executors, so the seed password must travel via setup data
const runId = Date.now().toString(36)

http.setResponseCallback(http.expectedStatuses(200, 202, 204, 400, 401, 404, 429))

export const options = {
  scenarios: {
    valid: {
      executor: 'constant-arrival-rate',
      rate: VALID_LOGINS_PER_MINUTE,
      timeUnit: '1m',
      duration: SMOKE_DURATION,
      preAllocatedVUs: 2,
      maxVUs: 4,
      exec: 'validLogin',
    },
    health: {
      executor: 'constant-arrival-rate',
      rate: HEALTH_PROBES_PER_MINUTE,
      timeUnit: '1m',
      duration: SMOKE_DURATION,
      preAllocatedVUs: 1,
      maxVUs: 2,
      exec: 'healthProbe',
    },
    login_burst: {
      executor: 'constant-arrival-rate',
      rate: LOGIN_BURST_RPS,
      timeUnit: '1s',
      duration: LOGIN_BURST_DURATION,
      startTime: '20s',
      preAllocatedVUs: 40,
      maxVUs: 300,
      exec: 'loginBurst',
    },
    register_burst: {
      executor: 'constant-arrival-rate',
      rate: REGISTER_BURST_RPS,
      timeUnit: '1s',
      duration: REGISTER_BURST_DURATION,
      startTime: '90s',
      preAllocatedVUs: 15,
      maxVUs: 70,
      exec: 'registerBurst',
    },
    sso: {
      executor: 'constant-arrival-rate',
      rate: SSO_PROBES_PER_MINUTE,
      timeUnit: '1m',
      duration: SMOKE_DURATION,
      preAllocatedVUs: 1,
      maxVUs: 2,
      exec: 'ssoSmoke',
    },
  },
  thresholds: {
    http_req_failed: ['rate==0'],
    'http_reqs{status:/^5/}': ['count==0'],
    'http_req_duration{scenario:valid}': ['p(95)<500'],
    'checks{scenario:valid}': ['rate==1'],
    'checks{scenario:health}': ['rate==1'],
    'checks{scenario:login_burst}': ['rate==1'],
    'checks{scenario:register_burst}': ['rate==1'],
    'checks{scenario:sso}': ['rate==1'],
    'http_req_duration{scenario:sso}': ['p(95)<500'],
    'http_reqs{scenario:login_burst,status:429}': ['count>0'],
    'http_reqs{scenario:register_burst,status:429}': ['count>0'],
  },
}

function jsonHeaders(sourceIp) {
  const headers = { 'Content-Type': 'application/json' }
  if (sourceIp) headers['X-Forwarded-For'] = sourceIp
  return headers
}

function postJson(url, payload, sourceIp) {
  return http.post(url, JSON.stringify(payload), { headers: jsonHeaders(sourceIp) })
}

function headerValue(response, name) {
  const target = name.toLowerCase()
  for (const key of Object.keys(response.headers)) {
    if (key.toLowerCase() === target) return response.headers[key]
  }
  return null
}

function safeJson(response) {
  try {
    return response.json()
  } catch (error) {
    return null
  }
}

function retryAfterSeconds(response) {
  const raw = headerValue(response, 'Retry-After')
  return raw === null || raw === '' ? 0 : parseInt(raw, 10)
}

function mailpitMessageSummaries() {
  const response = http.get(`${MAILPIT_URL}/api/v1/messages?limit=100`)
  if (response.status !== 200) fail(`Mailpit API ${MAILPIT_URL} answered ${response.status}`)
  try {
    return response.json().messages || []
  } catch (error) {
    fail(`Mailpit API ${MAILPIT_URL} returned a non-JSON body: ${error}`)
  }
}

export function setup() {
  const seedPassword = `K6smoke-${runId}-Valid1`
  const seedBase = Date.now()
  const thirdOctet = ((seedBase >>> 8) & 0xff) || 1
  const fourthOctet = seedBase & 0xff
  const users = []
  for (let i = 0; i < SEED_USER_COUNT; i += 1) {
    const user = {
      username: `k6seed${runId}u${i}`,
      email: `k6seed.${runId}.${i}@example.com`,
      sourceIp: `198.18.${thirdOctet}.${((fourthOctet + i) % 254) + 1}`,
    }
    const registered = postJson(REGISTER_ENDPOINT, { username: user.username, email: user.email }, user.sourceIp)
    if (registered.status !== 202) fail(`seed register ${user.username} -> ${registered.status} ${registered.body}`)
    users.push(user)
  }

  const deadline = Date.now() + MAILPIT_WAIT_MS
  for (;;) {
    for (const summary of mailpitMessageSummaries()) {
      for (const recipient of summary.To || []) {
        const user = users.find(u => u.email === recipient.Address && !u.verificationLetterId)
        if (user) user.verificationLetterId = summary.ID
      }
    }
    if (users.every(user => user.verificationLetterId)) break
    if (Date.now() > deadline) {
      const missing = users
        .filter(user => !user.verificationLetterId)
        .map(user => user.email)
        .join(', ')
      fail(`verification letters missing in Mailpit for ${missing}`)
    }
    sleep(2)
  }

  for (const user of users) {
    const letter = http.get(`${MAILPIT_URL}/api/v1/message/${user.verificationLetterId}`)
    const match = ((letter.json() || {}).Text || '').match(VERIFICATION_LINK_PATTERN)
    if (!match) fail(`verification link not found in the letter for ${user.email}`)
    const confirmed = postJson(CONFIRM_ENDPOINT, { token: match[1] }, user.sourceIp)
    if (confirmed.status !== 200) fail(`seed confirm ${user.email} -> ${confirmed.status} ${confirmed.body}`)
    const setupToken = (confirmed.json() || {}).setupToken
    const passwordSet = postJson(
      SET_PASSWORD_ENDPOINT,
      { setupToken, password: seedPassword, confirmPassword: seedPassword },
      user.sourceIp,
    )
    if (passwordSet.status !== 204) fail(`seed password ${user.email} -> ${passwordSet.status} ${passwordSet.body}`)
  }
  return { users, seedPassword }
}

export function validLogin(data) {
  const user = data.users[__ITER % data.users.length]
  const response = postJson(LOGIN_ENDPOINT, { identifier: user.username, password: data.seedPassword }, VALID_SOURCE_IP)
  check(response, { 'valid login under the limit is served (200)': r => r.status === 200 })
}

export function healthProbe() {
  const response = http.get(HEALTH_ENDPOINT)
  check(response, { 'health is 200 for the whole run': r => r.status === 200 })
}

export function loginBurst() {
  const response = postJson(LOGIN_ENDPOINT, { identifier: `absent_${runId}_${__VU}_${__ITER}`, password: `Absent-${runId}-Password1` }, null)
  check(response, {
    'login burst degrades to 401/429 only': r => r.status === 401 || r.status === 429,
    '429 carries Retry-After': r => r.status !== 429 || retryAfterSeconds(r) >= 1,
  })
}

export function registerBurst() {
  const response = postJson(
    REGISTER_ENDPOINT,
    { username: `sb${runId}u${__VU}i${__ITER}`, email: `sb.${runId}.${__VU}.${__ITER}@example.com` },
    null,
  )
  check(response, {
    'register burst degrades to 202/429 only': r => r.status === 202 || r.status === 429,
    '429 carries Retry-After': r => r.status !== 429 || retryAfterSeconds(r) >= 1,
  })
}

/**
 * 003-sso T050 (research §12): the SSO smoke slice — providers → authorize →
 * token-error over the three public JSON endpoints.
 *
 * The full SSO login flow (authorize → interactive login/consent at the IdP →
 * GET /auth/sso/callback 302 with a live handshake code → POST /auth/sso/token
 * 200) is NOT covered here and cannot be: it requires a real identity provider
 * with an interactive user login, which k6 cannot drive headlessly. That flow
 * is verified end-to-end by the SSO integration tests against MockIdP
 * (SsoFlowIT et al., research §12) and manually against the local dex IdP
 * (quickstart 003, scenarios S1–S6). Smoke asserts only what is IdP-independent:
 * providers 200, authorize 200 for a configured provider / the uniform 404 for
 * unknown ones, token-error 400 invalid_code for a never-issued code.
 */
export function ssoSmoke() {
  const providersResponse = http.get(SSO_PROVIDERS_ENDPOINT, { headers: { 'X-Forwarded-For': SSO_SOURCE_IP } })
  check(providersResponse, { 'sso providers is 200': r => r.status === 200 })
  const enabledProviders = safeJson(providersResponse)?.providers
  if (Array.isArray(enabledProviders) && enabledProviders.length > 0) {
    const authorizeResponse = postJson(SSO_AUTHORIZE_ENDPOINT, { providerId: enabledProviders[0].id }, SSO_SOURCE_IP)
    check(authorizeResponse, {
      'sso authorize on a configured provider is 200 with authorizationUrl': r =>
        r.status === 200 && Boolean(safeJson(r)?.authorizationUrl),
    })
  }
  const unknownProviderResponse = postJson(SSO_AUTHORIZE_ENDPOINT, { providerId: SSO_UNKNOWN_PROVIDER_ID }, SSO_SOURCE_IP)
  check(unknownProviderResponse, { 'sso authorize on an unknown provider is the uniform 404': r => r.status === 404 })
  const tokenResponse = postJson(SSO_TOKEN_ENDPOINT, { code: SSO_INVALID_HANDSHAKE_CODE }, SSO_SOURCE_IP)
  check(tokenResponse, {
    'sso token with an unknown handshake code is 400 invalid_code': r =>
      r.status === 400 && String(r.body).includes('invalid_code'),
  })
}
