import { http, HttpResponse } from 'msw'
import type { CalendarDay, Me, PastVisit } from '../api/types'

export interface MockState {
  me: Me | null
  days: CalendarDay[]
  history: PastVisit[]
  comment: string | null
  pendingLoginPhone: string | null
}

export const mockState: MockState = { me: null, days: [], history: [], comment: null, pendingLoginPhone: null }

/** Запросы к /api/calendar, зафиксированные для проверки диапазона дат в тестах. */
export const capturedCalendarRequests: { from: string; to: string }[] = []

export function resetMockState() {
  mockState.me = null
  mockState.days = []
  mockState.history = []
  mockState.comment = null
  mockState.pendingLoginPhone = null
  capturedCalendarRequests.length = 0
}

export const handlers = [
  http.get('/api/calendar', ({ request }) => {
    const url = new URL(request.url)
    capturedCalendarRequests.push({
      from: url.searchParams.get('from') ?? '',
      to: url.searchParams.get('to') ?? '',
    })
    return HttpResponse.json({ days: mockState.days })
  }),
  http.post('/api/auth/login', async ({ request }) => {
    const { phone } = (await request.json()) as { phone: string }
    mockState.pendingLoginPhone = phone
    return new HttpResponse(null, { status: 202 })
  }),
  http.post('/api/auth/verify', async ({ request }) => {
    const { phone, code } = (await request.json()) as { phone: string; code: string }
    if (phone !== mockState.pendingLoginPhone || code !== '123456') {
      return HttpResponse.json({ code: 'INVALID_CODE', message: 'Неверный код' }, { status: 400 })
    }
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
  http.post('/api/bookings', async ({ request }) => {
    const body = (await request.json()) as { checkIn: string; checkOut: string }
    if (mockState.me) {
      mockState.me.activeBooking = { id: 100, checkIn: body.checkIn, checkOut: body.checkOut, status: 'CONFIRMED' }
    }
    return HttpResponse.json({ bookingId: 100 }, { status: 201 })
  }),
  // конкретный путь /active — раньше динамического /:id, иначе тот перехватит первым
  http.patch('/api/bookings/active', async ({ request }) => {
    const { comment } = (await request.json()) as { comment: string | null }
    mockState.comment = comment && comment.trim() !== '' ? comment.trim() : null
    return new HttpResponse(null, { status: 204 })
  }),
  http.patch('/api/bookings/:id', () => new HttpResponse(null, { status: 204 })),
  http.delete('/api/bookings/:id', () => new HttpResponse(null, { status: 204 })),
  http.get('/api/me/bookings', () =>
    mockState.me
      ? HttpResponse.json({
          active: mockState.me.activeBooking
            ? { ...mockState.me.activeBooking, comment: mockState.comment ?? null }
            : null,
          history: mockState.history,
        })
      : HttpResponse.json({ code: 'UNAUTHORIZED', message: 'Требуется вход' }, { status: 401 })),
]
