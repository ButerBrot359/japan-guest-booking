import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, vi } from 'vitest'
import { capturedCalendarRequests, mockState } from '../test/handlers'
import App from '../App'

beforeEach(() => {
  // фиксируем дату — как в App.flow.test.tsx: 03:00Z = полдень JST
  vi.useFakeTimers({ shouldAdvanceTime: true })
  vi.setSystemTime(new Date('2026-09-01T03:00:00Z'))
})

afterEach(() => {
  vi.useRealTimers()
})

function renderApp() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={qc}><App /></QueryClientProvider>)
}

test('данные запрашиваются на год вперёд', async () => {
  renderApp()
  await waitFor(() => {
    expect(capturedCalendarRequests.at(-1)).toEqual({ from: '2026-09-01', to: '2027-09-01' })
  })
})

test('в десктопной сетке отрисованы 12 месяцев', async () => {
  renderApp()
  expect(await screen.findAllByText('Сентябрь 2026')).not.toHaveLength(0)
  expect(screen.getAllByText('Август 2027').length).toBeGreaterThan(0)
})

test('залогиненному в правой панели видны приветствие, карточка брони и «Прошлые поездки»', async () => {
  mockState.me = { phone: '+70000000001', name: 'Маша', role: 'FRIEND',
    telegramLinked: true, greeting: null,
    activeBooking: { id: 100, checkIn: '2026-09-10', checkOut: '2026-09-14', status: 'CONFIRMED' } }
  mockState.history = [{ checkIn: '2026-05-12', checkOut: '2026-05-19', nights: 7 }]
  renderApp()
  expect(await screen.findAllByText('Привет, Маша!')).not.toHaveLength(0)
  expect(screen.getAllByText(/10\/09\/2026/).length).toBeGreaterThan(0)
  expect(screen.getByText('подтверждена')).toBeInTheDocument()
  expect(await screen.findByText('Прошлые поездки')).toBeInTheDocument()
  expect(await screen.findByText(/12\/05\/2026/)).toBeInTheDocument()
})

test('анониму — контейнер без десктопного грида (lg:grid)', async () => {
  renderApp()
  await screen.findByText(/выбери даты/i)
  const container = document.querySelector('.min-h-dvh')
  expect(container).not.toBeNull()
  expect(container?.className).not.toMatch(/lg:grid\b/)
})
