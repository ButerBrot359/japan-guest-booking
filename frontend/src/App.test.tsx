import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
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
