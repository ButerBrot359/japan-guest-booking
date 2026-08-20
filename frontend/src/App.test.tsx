import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { mockState } from './test/handlers'
import App from './App'

export function renderApp() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={qc}><App /></QueryClientProvider>)
}

test('рендерит шапку и календарь', async () => {
  renderApp()
  expect(screen.getByText(/Домик в Японии/)).toBeInTheDocument()
  expect(await screen.findByText(/выбери даты/)).toBeInTheDocument()
})

it('залогиненному видны вкладки Календарь и Мои брони', async () => {
  mockState.me = { phone: '+70000000001', name: 'Маша', role: 'FRIEND',
    telegramLinked: true, greeting: null, activeBooking: null }
  renderApp()
  expect(await screen.findByRole('link', { name: 'Мои брони' })).toBeInTheDocument()
  expect(screen.getByRole('link', { name: 'Календарь' })).toBeInTheDocument()
})

it('анониму вкладка Мои брони не видна', async () => {
  renderApp()
  await screen.findByText(/выбери даты/i)
  expect(screen.queryByRole('link', { name: 'Мои брони' })).not.toBeInTheDocument()
})
