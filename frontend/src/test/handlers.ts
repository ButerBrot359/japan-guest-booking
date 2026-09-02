import { http, HttpResponse } from 'msw'
import type { AccessRequestRow, AdminUserRow, CalendarDay, Me, PastVisit, AdminBookingRow, BlockedPeriodRow } from '../api/types'

export interface MockState {
  me: Me | null
  days: CalendarDay[]
  history: PastVisit[]
  comment: string | null
  pendingLoginPhone: string | null
  adminGuests: AdminUserRow[]
  accessRequests: AccessRequestRow[]
  guestGreetings: Record<number, string[]>
  adminPassword: string   // пароль, который мок считает верным
  adminBookings: AdminBookingRow[]
  blockedPeriods: BlockedPeriodRow[]
}

export const mockState: MockState = {
  me: null, days: [], history: [], comment: null, pendingLoginPhone: null,
  adminGuests: [], accessRequests: [], guestGreetings: {}, adminPassword: 'admin',
  adminBookings: [], blockedPeriods: [],
}

/** Запросы к /api/calendar, зафиксированные для проверки диапазона дат в тестах. */
export const capturedCalendarRequests: { from: string; to: string }[] = []

export function resetMockState() {
  mockState.me = null
  mockState.days = []
  mockState.history = []
  mockState.comment = null
  mockState.pendingLoginPhone = null
  mockState.adminGuests = []
  mockState.accessRequests = []
  mockState.guestGreetings = {}
  mockState.adminPassword = 'admin'
  mockState.adminBookings = []
  mockState.blockedPeriods = []
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
  http.post('/api/auth/admin-login', async ({ request }) => {
    const { phone, password } = (await request.json()) as { phone: string; password: string }
    if (password !== mockState.adminPassword) {
      return HttpResponse.json({ code: 'INVALID_CREDENTIALS', message: '' }, { status: 401 })
    }
    mockState.me = {
      phone, name: 'Админ', role: 'ADMIN', telegramLinked: true, greeting: null, activeBooking: null,
    }
    return new HttpResponse(null, { status: 204 })
  }),
  http.get('/api/admin/access-requests', ({ request }) => {
    const status = new URL(request.url).searchParams.get('status') ?? 'PENDING'
    return HttpResponse.json(mockState.accessRequests.filter((r) => r.status === status))
  }),
  http.post('/api/admin/access-requests/:id/approve', ({ params }) => {
    const r = mockState.accessRequests.find((x) => x.id === Number(params.id))
    if (r) r.status = 'APPROVED'
    return new HttpResponse(null, { status: 204 })
  }),
  http.post('/api/admin/access-requests/:id/reject', ({ params }) => {
    const r = mockState.accessRequests.find((x) => x.id === Number(params.id))
    if (r) r.status = 'REJECTED'
    return new HttpResponse(null, { status: 204 })
  }),
  http.get('/api/admin/users', () => HttpResponse.json(mockState.adminGuests)),
  http.post('/api/admin/users', async ({ request }) => {
    const { phone, name } = (await request.json()) as { phone: string; name: string }
    if (mockState.adminGuests.some((g) => g.phone === phone && g.deletedAt == null)) {
      return HttpResponse.json({ code: 'ALREADY_MEMBER', message: '' }, { status: 409 })
    }
    const id = mockState.adminGuests.length + 1
    mockState.adminGuests.push({ id, phone, name, role: 'FRIEND', telegramLinked: false, deletedAt: null, greetings: [] })
    return new HttpResponse(null, { status: 201 })
  }),
  http.delete('/api/admin/users/:id', ({ params }) => {
    const g = mockState.adminGuests.find((x) => x.id === Number(params.id))
    if (g) g.deletedAt = '2026-08-22T00:00:00Z'
    return new HttpResponse(null, { status: 204 })
  }),
  http.get('/api/admin/users/:id/greetings', ({ params }) =>
    HttpResponse.json(mockState.guestGreetings[Number(params.id)] ?? [])),
  http.put('/api/admin/users/:id/greetings', async ({ params, request }) => {
    const { greetings } = (await request.json()) as { greetings: string[] }
    const id = Number(params.id)
    const cleaned = greetings.filter((g) => g.trim() !== '')
    mockState.guestGreetings[id] = cleaned
    // зеркалим бэкенд: список гостей отдаёт greetings из того же источника
    const guest = mockState.adminGuests.find((g) => g.id === id)
    if (guest) guest.greetings = cleaned
    return new HttpResponse(null, { status: 204 })
  }),
  http.get('/api/admin/bookings', () => HttpResponse.json(mockState.adminBookings)),
  http.post('/api/admin/bookings/:id/cancel', ({ params }) => {
    const b = mockState.adminBookings.find((x) => x.id === Number(params.id))
    if (!b || b.status !== 'CONFIRMED') {
      return HttpResponse.json({ code: 'BOOKING_EXPIRED', message: 'Бронь уже неактивна' }, { status: 409 })
    }
    b.status = 'CANCELLED'
    return new HttpResponse(null, { status: 204 })
  }),
  http.post('/api/admin/bookings/:id/reschedule', async ({ params, request }) => {
    const { checkIn, checkOut } = (await request.json()) as { checkIn: string; checkOut: string }
    const b = mockState.adminBookings.find((x) => x.id === Number(params.id))
    if (b) { b.checkIn = checkIn; b.checkOut = checkOut }
    return new HttpResponse(null, { status: 204 })
  }),
  http.get('/api/admin/blocked-periods', () => HttpResponse.json(mockState.blockedPeriods)),
  http.post('/api/admin/blocked-periods', async ({ request }) => {
    const { startDate, endDate, reason } =
      (await request.json()) as { startDate: string; endDate: string; reason?: string }
    const row: BlockedPeriodRow = {
      id: mockState.blockedPeriods.length + 1,
      startDate, endDate, reason: reason ?? null, createdAt: '2026-08-22T00:00:00Z',
    }
    mockState.blockedPeriods.push(row)
    return HttpResponse.json(row, { status: 201 })
  }),
  http.delete('/api/admin/blocked-periods/:id', ({ params }) => {
    const i = mockState.blockedPeriods.findIndex((x) => x.id === Number(params.id))
    if (i >= 0) mockState.blockedPeriods.splice(i, 1)
    return new HttpResponse(null, { status: 204 })
  }),
]
