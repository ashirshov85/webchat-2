/**
 * Контекст проведения профилей участников конференции (009, T073):
 * ConferencePage грузит Map один раз на монтаж (useParticipantProfiles
 * по chatId №45-вида) и раздаёт её вниз — ParticipantAvatar читает её
 * в тайлах ConferenceRoom и строках ParticipantsPanel. null — провайдера
 * нет: гость-наблюдатель GuestJoinPage (API чатов недоступны, FR-010),
 * все аватары фолбэком из participant.name.
 */
import { createContext } from 'react'
import type { ParticipantProfiles } from './hooks/useParticipantProfiles'

export const ParticipantProfilesContext = createContext<ParticipantProfiles | null>(null)
