import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { mockState } from '../../test/handlers'
import { server } from '../../test/setup'
import { AdminBookings } from './AdminBookings'

beforeEach(() => {
  // фиксируем дату — как в App.flow.test.tsx: 03:00Z = полдень JST
  vi.useFakeTimers({ shouldAdvanceTime: true })
  vi.setSystemTime(new Date('2026-09-01T03:00:00Z'))
})

afterEach(() => {
  vi.useRealTimers()
})

function renderSection() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={qc}><AdminBookings /></QueryClientProvider>)
}

const booking = (over: Partial<(typeof mockState.adminBookings)[number]> = {}) => ({
  id: 1, guestName: 'Маша', guestPhone: '+79990000001',
  checkIn: '2026-09-10', checkOut: '2026-09-12', status: 'CONFIRMED' as const, comment: null,
  ...over,
})

// в jsdom отрендерены оба календаря (мобильный и десктопный) — берём первую кнопку
const dayButton = (label: RegExp) => screen.getAllByRole('button', { name: label })[0]

test('показывает только активные брони', async () => {
  mockState.adminBookings = [
    booking(),
    booking({ id: 2, guestName: 'Отменённая', status: 'CANCELLED' }),
    booking({ id: 3, guestName: 'Прошлая', checkIn: '2026-01-05', checkOut: '2026-01-08' }),
  ]
  renderSection()
  expect(await screen.findByText('Маша')).toBeInTheDocument()
  expect(screen.queryByText('Отменённая')).not.toBeInTheDocument()
  expect(screen.queryByText('Прошлая')).not.toBeInTheDocument()
})

test('пустое состояние', async () => {
  renderSection()
  expect(await screen.findByText('Активных броней нет')).toBeInTheDocument()
})

test('отмена после подтверждения убирает бронь из списка', async () => {
  mockState.adminBookings = [booking()]
  renderSection()
  await userEvent.click(await screen.findByRole('button', { name: 'Отменить' }))
  expect(screen.getByText(/Отменить бронь гостя Маша/)).toBeInTheDocument()
  await userEvent.click(screen.getByRole('button', { name: 'Да, отменить' }))
  await waitFor(() => expect(screen.queryByText('Маша')).not.toBeInTheDocument())
})

test('перенос: выбор дат в модалке обновляет бронь', async () => {
  mockState.adminBookings = [booking()]
  mockState.days = ['2026-09-10', '2026-09-11', '2026-09-12'].map((date) => ({
    date, status: 'BOOKED' as const, guestName: 'Маша', mine: false,
  }))
  renderSection()
  await userEvent.click(await screen.findByRole('button', { name: 'Перенести' }))
  expect(screen.getByText(/Сейчас: 10\/09\/2026 → 12\/09\/2026/)).toBeInTheDocument()
  // дни самой брони освобождены (daysWithoutBooking) — их можно выбрать заново
  expect(screen.getAllByRole('button', { name: /^11 сентября/ })[0]).toBeEnabled()
  await userEvent.click(dayButton(/^20 сентября/))
  await userEvent.click(dayButton(/^23 сентября/))
  await userEvent.click(screen.getByRole('button', { name: 'Перенести бронь' }))
  // мок обновил бронь — после инвалидации таблица показывает новые даты
  expect(await screen.findByText(/20\/09\/2026 → 23\/09\/2026/)).toBeInTheDocument()
})

test('перенос на занятые даты показывает ошибку DATES_TAKEN', async () => {
  mockState.adminBookings = [booking()]
  server.use(http.post('/api/admin/bookings/:id/reschedule', () =>
    HttpResponse.json({ code: 'DATES_TAKEN', message: '' }, { status: 409 })))
  renderSection()
  await userEvent.click(await screen.findByRole('button', { name: 'Перенести' }))
  await userEvent.click(dayButton(/^20 сентября/))
  await userEvent.click(dayButton(/^23 сентября/))
  await userEvent.click(screen.getByRole('button', { name: 'Перенести бронь' }))
  expect(await screen.findByText(/Эти даты заняты/)).toBeInTheDocument()
})

test('ссылка «Выгрузить в Excel» ведёт на export и видна даже без активных броней', async () => {
  renderSection()
  await screen.findByText('Активных броней нет')
  const link = screen.getByRole('link', { name: 'Выгрузить в Excel' })
  expect(link).toHaveAttribute('href', '/api/admin/bookings/export')
})
