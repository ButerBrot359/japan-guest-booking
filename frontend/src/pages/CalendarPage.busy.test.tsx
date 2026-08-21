import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, vi } from 'vitest'
import { mockState } from '../test/handlers'
import App from '../App'

beforeEach(() => {
  // фиксируем дату — как в App.flow.test.tsx / CalendarPage.desktop.test.tsx
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

function loginAs(me: Partial<NonNullable<typeof mockState.me>>) {
  mockState.me = {
    phone: '+79990001122', name: 'Маша', role: 'FRIEND', telegramLinked: true,
    greeting: null, activeBooking: null, ...me,
  }
}

test('клик по занятому дню показывает кто гостит', async () => {
  loginAs({})
  mockState.days = [
    { date: '2026-09-10', status: 'BOOKED', guestName: 'Миша', mine: false },
    { date: '2026-09-11', status: 'BOOKED', guestName: 'Миша', mine: false },
    { date: '2026-09-12', status: 'BOOKED', guestName: 'Миша', mine: false },
  ]
  renderApp()
  await userEvent.click((await screen.findAllByRole('button', { name: /10 сентября.*занято/i }))[0])
  expect(await screen.findByText(/Гостит Миша/)).toBeInTheDocument()
  expect(screen.getByText(/10\/09\/2026 → 12\/09\/2026/)).toBeInTheDocument()
})
