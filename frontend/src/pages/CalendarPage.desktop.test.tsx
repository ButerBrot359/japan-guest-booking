import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, vi } from 'vitest'
import { capturedCalendarRequests } from '../test/handlers'
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
