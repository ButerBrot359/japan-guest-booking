import { http, HttpResponse } from 'msw'
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

export const handlers = [
  http.get('/api/calendar', () => HttpResponse.json({ days: mockState.days })),
  http.post('/api/auth/login', async ({ request }) => {
    const { phone } = (await request.json()) as { phone: string }
    mockState.me = {
      phone, name: 'Маша', role: 'FRIEND', telegramLinked: true,
      greeting: null, activeBooking: null,
    }
    return new HttpResponse(null, { status: 204 })
  }),
  http.post('/api/auth/logout', () => {
    mockState.me = null
    return new HttpResponse(null, { status: 204 })
  }),
  http.get('/api/me', () =>
    mockState.me
      ? HttpResponse.json(mockState.me)
      : HttpResponse.json({ code: 'UNAUTHORIZED', message: 'Требуется вход' }, { status: 401 })),
  http.post('/api/access-requests', () => new HttpResponse(null, { status: 201 })),
]
