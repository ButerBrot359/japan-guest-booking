import type { CalendarDay, Me } from '../api/types'

export interface MockState {
  me: Me | null
  days: CalendarDay[]
}

export const mockState: MockState = { me: null, days: [] }

export function resetMockState() {
  mockState.me = null
  mockState.days = []
}

export const handlers = [] as import('msw').RequestHandler[]
