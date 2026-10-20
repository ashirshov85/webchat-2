import { describe, expect, it } from 'vitest'
import { parseParticipantIdentity } from '../identity'

/**
 * 009, T073 — клиентский парсер префиксов identity (зеркало
 * ConferenceIdentity backend, research §4): LiveKit-участник несёт
 * `u:{userId}:{connId}` (член чата, минт №47) или `g:{connId}` (гость,
 * №55). Сегменты обязаны быть UUID (identity минтится сервером);
 * чужие/битые значения → unknown без выброса — внешний ввод.
 */

const ALICE = '11111111-1111-4111-8111-111111111111'
const CONN = '22222222-2222-4222-8222-222222222222'

describe('parseParticipantIdentity (009 T073 — зеркало ConferenceIdentity)', () => {
  it('u:{userId}:{connId} — член чата: userId вычленен из connection-identity', () => {
    expect(parseParticipantIdentity(`u:${ALICE}:${CONN}`)).toEqual({
      kind: 'member',
      userId: ALICE,
    })
  })

  it('u:{userId} — префикс-принципал члена сворачивается к тому же члену', () => {
    expect(parseParticipantIdentity(`u:${ALICE}`)).toEqual({ kind: 'member', userId: ALICE })
  })

  it('g:{connId} — гость №55', () => {
    expect(parseParticipantIdentity(`g:${CONN}`)).toEqual({ kind: 'guest' })
  })

  it('битые сегменты (не UUID) и чужие префиксы — unknown, без выброса', () => {
    expect(parseParticipantIdentity('u:conn-1:x')).toEqual({ kind: 'unknown' })
    expect(parseParticipantIdentity(`u:${ALICE}:conn-1`)).toEqual({ kind: 'unknown' })
    expect(parseParticipantIdentity('g:conn-guest-1')).toEqual({ kind: 'unknown' })
    expect(parseParticipantIdentity('x:123')).toEqual({ kind: 'unknown' })
    expect(parseParticipantIdentity('')).toEqual({ kind: 'unknown' })
    expect(parseParticipantIdentity('u:')).toEqual({ kind: 'unknown' })
  })
})
