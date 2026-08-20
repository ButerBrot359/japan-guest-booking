import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { vi } from 'vitest'
import type { Me } from '../api/types'
import { Header } from './Header'

const me: Me = {
  phone: '+79990001122', name: 'Аня', role: 'FRIEND',
  telegramLinked: true, greeting: null, activeBooking: null,
}

function renderHeader(props: { me: Me | null; onLoginClick?: () => void }) {
  const qc = new QueryClient()
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter><Header {...props} /></MemoryRouter>
    </QueryClientProvider>,
  )
}

test('залогиненному в шапке видна кнопка «выйти»', () => {
  renderHeader({ me })
  expect(screen.getByRole('button', { name: 'выйти' })).toBeInTheDocument()
})

test('анониму в шапке кнопка «выйти» не видна — только «Войти»', () => {
  renderHeader({ me: null, onLoginClick: vi.fn() })
  expect(screen.queryByRole('button', { name: 'выйти' })).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Войти' })).toBeInTheDocument()
})
